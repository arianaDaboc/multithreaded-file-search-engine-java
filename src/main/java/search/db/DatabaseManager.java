package search.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Creates the SQLite schema and supplies short-lived JDBC connections. */
public final class DatabaseManager {
    private final String jdbcUrl;

    public DatabaseManager(Path databaseFile) throws IOException, SQLException {
        Path absolutePath = databaseFile.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.jdbcUrl = "jdbc:sqlite:" + absolutePath;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS search_runs (
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS matched_files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        search_run_id INTEGER NOT NULL REFERENCES search_runs(id) ON DELETE CASCADE,
                        relative_path TEXT NOT NULL,
                        occurrences INTEGER NOT NULL,
                        UNIQUE(search_run_id, relative_path)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS line_matches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        matched_file_id INTEGER NOT NULL REFERENCES matched_files(id) ON DELETE CASCADE,
                        line_number INTEGER NOT NULL,
                        occurrences INTEGER NOT NULL,
                        line_text TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_search_runs_created_at ON search_runs(created_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_matched_files_run_id ON matched_files(search_run_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_line_matches_file_id ON line_matches(matched_file_id)");
            ensureColumn(connection, "lines_scanned", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "bytes_scanned", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "file_processing_nanos", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "error_count", "INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void ensureColumn(Connection connection, String columnName, String definition) throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement(); var columns = statement.executeQuery("PRAGMA table_info(search_runs)")) {
            while (columns.next()) {
                if (columnName.equalsIgnoreCase(columns.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE search_runs ADD COLUMN " + columnName + " " + definition);
            }
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }
}
