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
        double elapsedSeconds) { }
