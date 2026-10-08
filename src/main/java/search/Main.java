package search;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import search.db.DatabaseManager;
import search.db.SearchHistoryRepository;
import search.db.SearchRunSummary;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); usage(); System.exit(2); }
        catch (Exception e) { System.err.println("Error: " + e.getMessage()); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) { usage(); return; }
        if (args[0].equalsIgnoreCase("history")) { runHistory(args); return; }
        if (args.length < 2) { usage(); return; }
        Path root = Path.of(args[0]); String query = args[1]; boolean caseSensitive = true;
        int threads = Runtime.getRuntime().availableProcessors();
        Set<String> extensions = Set.of(), excludes = Set.of(); List<Integer> benchmark = null;
        boolean regex = false; Path csv = null, databaseFile = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--ignore-case" -> caseSensitive = false;
                case "--threads" -> threads = Integer.parseInt(requireValue(args, ++i, "--threads"));
                case "--extensions" -> extensions = new LinkedHashSet<>(Arrays.asList(requireValue(args, ++i, "--extensions").split(",")));
                case "--exclude" -> excludes = new LinkedHashSet<>(Arrays.asList(requireValue(args, ++i, "--exclude").split(",")));
                case "--regex" -> regex = true;
                case "--benchmark" -> benchmark = Arrays.stream(requireValue(args, ++i, "--benchmark").split(",")).map(Integer::parseInt).distinct().toList();
                case "--csv" -> csv = Path.of(requireValue(args, ++i, "--csv"));
                case "--database" -> databaseFile = Path.of(requireValue(args, ++i, "--database"));
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        SearchConfig config = new SearchConfig(root, query, caseSensitive, extensions, excludes, regex);
        SearchEngine engine = new SearchEngine(config);
        SearchHistoryRepository historyRepository = databaseFile == null ? null
                : new SearchHistoryRepository(new DatabaseManager(databaseFile));
        if (benchmark != null) {
            System.out.println("Benchmark (results below are from the final run):");
            SearchEngine.SearchReport last = null;
            int lastWorkers = benchmark.get(0);
            StringBuilder csvRows = new StringBuilder("threads,time_seconds,files_scanned,files_matched,occurrences,lines_scanned,bytes_scanned,files_per_second,avg_file_time_seconds,error_count,cancelled\n");
            for (int workers : benchmark) {
                lastWorkers = workers;
                last = runWithShutdownCancellation(engine, workers);
                System.out.printf(java.util.Locale.ROOT, "%2d threads: %.3f s | %d files scanned | %d occurrences%n", workers, last.elapsedSeconds(), last.filesScanned(), last.occurrences());
                if (!last.cancelled()) saveToDatabase(historyRepository, config, last, workers);
                csvRows.append(workers).append(',')
                        .append(String.format(java.util.Locale.ROOT, "%.6f", last.elapsedSeconds())).append(',')
                        .append(last.filesScanned()).append(',').append(last.results().size()).append(',')
                        .append(last.occurrences()).append(',').append(last.linesScanned()).append(',')
                        .append(last.bytesScanned()).append(',')
                        .append(String.format(java.util.Locale.ROOT, "%.3f", last.filesPerSecond())).append(',')
                        .append(String.format(java.util.Locale.ROOT, "%.6f", last.averageFileSeconds())).append(',')
                        .append(last.errorCount()).append(',').append(last.cancelled()).append('\n');
                if (last.cancelled()) break;
            }
            if (csv != null) { Path parent = csv.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent); Files.writeString(csv, csvRows, StandardCharsets.UTF_8); System.out.println("CSV saved: " + csv.toAbsolutePath()); }
            if (last != null) printResults(config, last, lastWorkers);
        } else {
            SearchEngine.SearchReport report = runWithShutdownCancellation(engine, threads);
            printResults(config, report, threads);
            if (!report.cancelled()) saveToDatabase(historyRepository, config, report, threads);
        }
    }

    private static SearchEngine.SearchReport runWithShutdownCancellation(SearchEngine engine, int threads)
            throws Exception {
        SearchControl control = new SearchControl();
        Thread coordinator = Thread.currentThread();
        Thread shutdownHook = new Thread(() -> {
            System.out.println("\nCancellation requested; stopping file search…");
            control.cancel();
            try { coordinator.join(5_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "search-cancellation-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            return engine.search(threads, control);
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* JVM shutdown is already running. */ }
        }
    }

    private static void saveToDatabase(SearchHistoryRepository repository, SearchConfig config,
                                       SearchEngine.SearchReport report, int threads) throws Exception {
        if (repository != null) {
            long runId = repository.save(config, report, threads);
            System.out.println("Saved search run to database as #" + runId);
        }
    }

    private static void runHistory(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: history <database-file> [--limit N]");
        Path databaseFile = Path.of(args[1]);
        int limit = 10;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--limit")) limit = Integer.parseInt(requireValue(args, ++i, "--limit"));
            else throw new IllegalArgumentException("Unknown history option: " + args[i]);
        }
        SearchHistoryRepository repository = new SearchHistoryRepository(new DatabaseManager(databaseFile));
        List<SearchRunSummary> runs = repository.findLatest(limit);
        if (runs.isEmpty()) {
            System.out.println("No saved searches yet.");
            return;
        }
        System.out.println("Recent searches:");
        System.out.printf("%-5s %-19s %-20s %7s %8s %8s %10s %8s %10s %8s %10s %12s %10s%n",
                "ID", "Created at", "Query", "Threads", "Scanned", "Matched", "Occurrences",
                "Lines", "Bytes", "Errors", "Time (s)", "Files/s", "Avg/file ms");
        for (SearchRunSummary run : runs) {
            System.out.printf(java.util.Locale.ROOT, "%-5d %-19s %-20s %7d %8d %8d %10d %8d %10s %8d %10.3f %12.2f %10.3f%n",
                    run.id(), run.createdAt(), truncate(run.query(), 24), run.threads(), run.filesScanned(),
                    run.filesMatched(), run.occurrences(), run.linesScanned(), formatBytes(run.bytesScanned()),
                    run.errorCount(), run.elapsedSeconds(), run.filesPerSecond(), run.averageFileSeconds() * 1_000);
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }

    private static void printResults(SearchConfig config, SearchEngine.SearchReport report, int threads) {
        System.out.printf(java.util.Locale.ROOT, "%nDirectory: %s%nQuery: %s (%s)%nFiles scanned: %d%nFiles matched: %d%nOccurrences: %d%nLines scanned: %d%nBytes scanned: %s%nTime: %.3f s%nFiles/second: %.2f%nAverage file processing: %.3f ms%nErrors: %d%nThreads: %d%n",
                config.root(), config.query(), config.caseSensitive() ? "case-sensitive" : "case-insensitive",
                report.filesScanned(), report.results().size(), report.occurrences(), report.linesScanned(),
                formatBytes(report.bytesScanned()), report.elapsedSeconds(), report.filesPerSecond(),
                report.averageFileSeconds() * 1_000, report.errorCount(), threads);
        if (report.cancelled()) System.out.println("Search cancelled; showing completed results.");
        for (SearchResult result : report.results()) {
            System.out.println("\n" + config.root().relativize(result.file()) + " (" + result.occurrences() + " occurrences)");
            for (SearchResult.LineMatch match : result.matches()) System.out.printf("  line %d (%d): %s%n", match.lineNumber(), match.occurrences(), match.text());
        }
        for (SearchError error : report.errors()) {
            Path displayPath = error.file().startsWith(config.root())
                    ? config.root().relativize(error.file()) : error.file();
            System.err.printf("%nWARNING: Could not read file:%n%s%nReason: %s%nContinuing search...%n",
                    displayPath, error.reason());
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_000) return bytes + " B";
        if (bytes < 1_000_000) return String.format(java.util.Locale.ROOT, "%.2f kB", bytes / 1_000.0);
        if (bytes < 1_000_000_000) return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_000_000.0);
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0);
    }

    private static void usage() {
        System.out.println("Usage: mvn exec:java -Dexec.args=\"<directory> <query> [options]\"");
        System.out.println("Options: --ignore-case --regex --threads N --extensions java,txt --exclude .git,target --benchmark 1,2,4,8 --csv results.csv --database search-history.db");
        System.out.println("History: mvn exec:java -Dexec.args=\"history <database-file> [--limit N]\"");
    }
}
