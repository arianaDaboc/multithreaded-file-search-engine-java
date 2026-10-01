package search;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); usage(); System.exit(2); }
        catch (Exception e) { System.err.println("Error: " + e.getMessage()); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2 || args[0].equals("--help")) { usage(); return; }
        Path root = Path.of(args[0]); String query = args[1]; boolean caseSensitive = true;
        int threads = Runtime.getRuntime().availableProcessors();
        Set<String> extensions = Set.of(), excludes = Set.of(); List<Integer> benchmark = null;
        boolean regex = false; Path csv = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--ignore-case" -> caseSensitive = false;
                case "--threads" -> threads = Integer.parseInt(requireValue(args, ++i, "--threads"));
                case "--extensions" -> extensions = new LinkedHashSet<>(Arrays.asList(requireValue(args, ++i, "--extensions").split(",")));
                case "--exclude" -> excludes = new LinkedHashSet<>(Arrays.asList(requireValue(args, ++i, "--exclude").split(",")));
                case "--regex" -> regex = true;
                case "--benchmark" -> benchmark = Arrays.stream(requireValue(args, ++i, "--benchmark").split(",")).map(Integer::parseInt).distinct().toList();
                case "--csv" -> csv = Path.of(requireValue(args, ++i, "--csv"));
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        SearchConfig config = new SearchConfig(root, query, caseSensitive, extensions, excludes, regex);
        SearchEngine engine = new SearchEngine(config);
        if (benchmark != null) {
            System.out.println("Benchmark (results below are from the final run):");
            SearchEngine.SearchReport last = null;
            StringBuilder csvRows = new StringBuilder("threads,time_seconds,files_scanned,files_matched,occurrences\n");
            for (int workers : benchmark) {
                last = engine.search(workers);
                System.out.printf("%2d threads: %.3f s | %d files scanned | %d occurrences%n", workers, last.elapsedSeconds(), last.filesScanned(), last.occurrences());
                csvRows.append(workers).append(',').append(String.format(java.util.Locale.ROOT, "%.6f", last.elapsedSeconds())).append(',').append(last.filesScanned()).append(',').append(last.results().size()).append(',').append(last.occurrences()).append('\n');
            }
            if (csv != null) { Path parent = csv.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent); Files.writeString(csv, csvRows, StandardCharsets.UTF_8); System.out.println("CSV saved: " + csv.toAbsolutePath()); }
            if (last != null) printResults(config, last, benchmark.get(benchmark.size() - 1));
        } else printResults(config, engine.search(threads), threads);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }

    private static void printResults(SearchConfig config, SearchEngine.SearchReport report, int threads) {
        System.out.printf("%nDirectory: %s%nQuery: %s (%s)%nFiles scanned: %d%nFiles matched: %d%nOccurrences: %d%nTime: %.3f s%nThreads: %d%n",
                config.root(), config.query(), config.caseSensitive() ? "case-sensitive" : "case-insensitive", report.filesScanned(), report.results().size(), report.occurrences(), report.elapsedSeconds(), threads);
        for (SearchResult result : report.results()) {
            System.out.println("\n" + config.root().relativize(result.file()) + " (" + result.occurrences() + " occurrences)");
            for (SearchResult.LineMatch match : result.matches()) System.out.printf("  line %d (%d): %s%n", match.lineNumber(), match.occurrences(), match.text());
        }
        if (!report.errors().isEmpty()) { System.out.println("\nUnreadable files: " + report.errors().size()); report.errors().forEach(System.err::println); }
    }

    private static void usage() {
        System.out.println("Usage: mvn exec:java -Dexec.args=\"<directory> <query> [options]\"");
        System.out.println("Options: --ignore-case --regex --threads N --extensions java,txt --exclude .git,target --benchmark 1,2,4,8 --csv results.csv");
    }
}
