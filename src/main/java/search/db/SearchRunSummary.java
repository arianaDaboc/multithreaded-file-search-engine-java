package search.db;

/** A compact search-run record used by the history view. */
public record SearchRunSummary(
        long id,
        String createdAt,
        String query,
        int threads,
        int filesScanned,
        int filesMatched,
        long occurrences,
        double elapsedSeconds,
        long linesScanned,
        long bytesScanned,
        long totalFileProcessingNanos,
        int errorCount) {

    public SearchRunSummary(long id, String createdAt, String query, int threads, int filesScanned,
                            int filesMatched, long occurrences, double elapsedSeconds) {
        this(id, createdAt, query, threads, filesScanned, filesMatched, occurrences, elapsedSeconds,
                0, 0, 0, 0);
    }

    public double averageFileSeconds() {
        return filesScanned == 0 ? 0 : totalFileProcessingNanos / (double) filesScanned / 1_000_000_000.0;
    }

    public double filesPerSecond() {
        return elapsedSeconds == 0 ? 0 : filesScanned / elapsedSeconds;
    }
}
