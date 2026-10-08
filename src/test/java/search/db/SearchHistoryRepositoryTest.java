package search.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import search.SearchConfig;
import search.SearchError;
import search.SearchEngine;
import search.SearchResult;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchHistoryRepositoryTest {
    @TempDir Path temp;

    @Test void savesSearchSummaryAndMatchingLinesAtomically() throws Exception {
        Path root = temp.resolve("source");
        Path matchedFile = root.resolve("Example.java");
        var config = new SearchConfig(root, "TODO", true, Set.of("java"));
        var line = new SearchResult.LineMatch(12, "// TODO: improve this", 1);
        var match = new SearchResult(matchedFile, List.of(line), 1);
        var report = new SearchEngine.SearchReport(4, List.of(match), 1,
                List.of(new SearchError(root.resolve("broken.txt"), "Access denied.")), 2_500_000,
                false, 27, 4_096, 8_000_000);
        var database = new DatabaseManager(temp.resolve("history.db"));
        var repository = new SearchHistoryRepository(database);

        long runId = repository.save(config, report, 4);

        var history = repository.findLatest(10);
        assertEquals(1, history.size());
        assertEquals(runId, history.get(0).id());
        assertEquals("TODO", history.get(0).query());
        assertEquals(4, history.get(0).threads());
        assertEquals(1, history.get(0).filesMatched());
        assertEquals(1, history.get(0).occurrences());
        assertEquals(0.0025, history.get(0).elapsedSeconds(), 0.000001);
        assertEquals(27, history.get(0).linesScanned());
        assertEquals(4_096, history.get(0).bytesScanned());
        assertEquals(8_000_000, history.get(0).totalFileProcessingNanos());
        assertEquals(1, history.get(0).errorCount());

        try (var connection = database.openConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT relative_path, occurrences FROM matched_files WHERE search_run_id = " + runId)) {
                row.next();
                assertEquals("Example.java", row.getString("relative_path"));
                assertEquals(1, row.getInt("occurrences"));
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT line_number, line_text FROM line_matches WHERE matched_file_id = (SELECT id FROM matched_files WHERE search_run_id = ?)") ) {
                query.setLong(1, runId);
                try (ResultSet row = query.executeQuery()) {
                    row.next();
                    assertEquals(12, row.getLong("line_number"));
                    assertEquals("// TODO: improve this", row.getString("line_text"));
                }
            }
        }
    }

    @Test void upgradesExistingHistoryDatabaseWithMetricColumns() throws Exception {
        Path file = temp.resolve("legacy-history.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE search_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        root_path TEXT NOT NULL,
                        query_text TEXT NOT NULL,
                        case_sensitive INTEGER NOT NULL,
                        regex INTEGER NOT NULL,
                        thread_count INTEGER NOT NULL,
                        files_scanned INTEGER NOT NULL,
                        files_matched INTEGER NOT NULL,
                        occurrences INTEGER NOT NULL,
                        elapsed_nanos INTEGER NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("INSERT INTO search_runs(root_path, query_text, case_sensitive, regex, thread_count, files_scanned, files_matched, occurrences, elapsed_nanos) VALUES ('src', 'x', 1, 0, 2, 4, 1, 1, 1000)");
        }

        var repository = new SearchHistoryRepository(new DatabaseManager(file));
        var run = repository.findLatest(1).get(0);

        assertEquals(4, run.filesScanned());
        assertEquals(0, run.linesScanned());
        assertEquals(0, run.bytesScanned());
        assertEquals(0, run.errorCount());
    }
}
