package search.db;

import search.SearchConfig;
import search.SearchEngine;
import search.SearchResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** JDBC persistence for search runs, matching files, and matching lines. */
public final class SearchHistoryRepository {
    private final DatabaseManager database;

    public SearchHistoryRepository(DatabaseManager database) { this.database = database; }

    public long save(SearchConfig config, SearchEngine.SearchReport report, int threads) throws SQLException {
        String runSql = """
                INSERT INTO search_runs(root_path, query_text, case_sensitive, regex, thread_count,
                                        files_scanned, files_matched, occurrences, elapsed_nanos,
                                        lines_scanned, bytes_scanned, file_processing_nanos, error_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                long runId;
                try (PreparedStatement statement = connection.prepareStatement(runSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, config.root().toString());
                    statement.setString(2, config.query());
                    statement.setInt(3, config.caseSensitive() ? 1 : 0);
                    statement.setInt(4, config.regex() ? 1 : 0);
                    statement.setInt(5, threads);
                    statement.setInt(6, report.filesScanned());
                    statement.setInt(7, report.results().size());
                    statement.setLong(8, report.occurrences());
                    statement.setLong(9, report.elapsedNanos());
                    statement.setLong(10, report.linesScanned());
                    statement.setLong(11, report.bytesScanned());
                    statement.setLong(12, report.totalFileProcessingNanos());
                    statement.setInt(13, report.errorCount());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("SQLite did not return a search run ID.");
                        runId = keys.getLong(1);
                    }
                }
                persistMatches(connection, runId, config, report.results());
                connection.commit();
                return runId;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void persistMatches(Connection connection, long runId, SearchConfig config, List<SearchResult> results) throws SQLException {
        String fileSql = "INSERT INTO matched_files(search_run_id, relative_path, occurrences) VALUES (?, ?, ?)";
        String lineSql = "INSERT INTO line_matches(matched_file_id, line_number, occurrences, line_text) VALUES (?, ?, ?, ?)";
        try (PreparedStatement fileStatement = connection.prepareStatement(fileSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement lineStatement = connection.prepareStatement(lineSql)) {
            for (SearchResult result : results) {
                fileStatement.setLong(1, runId);
                fileStatement.setString(2, config.root().relativize(result.file()).toString());
                fileStatement.setLong(3, result.occurrences());
                fileStatement.executeUpdate();
                long fileId;
                try (ResultSet keys = fileStatement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("SQLite did not return a matched file ID.");
                    fileId = keys.getLong(1);
                }
                for (SearchResult.LineMatch match : result.matches()) {
                    lineStatement.setLong(1, fileId);
                    lineStatement.setLong(2, match.lineNumber());
                    lineStatement.setInt(3, match.occurrences());
                    lineStatement.setString(4, match.text());
                    lineStatement.addBatch();
                }
                lineStatement.executeBatch();
            }
        }
    }

    public List<SearchRunSummary> findLatest(int limit) throws SQLException {
        if (limit < 1) throw new IllegalArgumentException("History limit must be at least 1.");
        String sql = """
                SELECT id, created_at, query_text, thread_count, files_scanned, files_matched,
                       occurrences, elapsed_nanos, lines_scanned, bytes_scanned,
                       file_processing_nanos, error_count
                FROM search_runs
                ORDER BY id DESC
                LIMIT ?
                """;
        List<SearchRunSummary> runs = new ArrayList<>();
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    runs.add(new SearchRunSummary(
                            rows.getLong("id"), rows.getString("created_at"), rows.getString("query_text"),
                            rows.getInt("thread_count"), rows.getInt("files_scanned"), rows.getInt("files_matched"),
                            rows.getLong("occurrences"), rows.getLong("elapsed_nanos") / 1_000_000_000.0,
                            rows.getLong("lines_scanned"), rows.getLong("bytes_scanned"),
                            rows.getLong("file_processing_nanos"), rows.getInt("error_count")));
                }
            }
        }
        return List.copyOf(runs);
    }
}
