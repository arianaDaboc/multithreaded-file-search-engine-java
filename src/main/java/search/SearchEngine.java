package search;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchEngine {
    private final SearchConfig config;
    private final Pattern pattern;
    public SearchEngine(SearchConfig config) {
        this.config = config;
        int flags = config.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        this.pattern = config.regex() ? Pattern.compile(config.query(), flags) : Pattern.compile(Pattern.quote(config.query()), flags);
    }

    public SearchReport search(int threads) throws IOException, InterruptedException {
        if (threads < 1) throw new IllegalArgumentException("Thread count must be at least 1.");
        List<Path> files = discoverFiles();
        long started = System.nanoTime();
        List<SearchResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<FileOutcome> completed = new ExecutorCompletionService<>(pool);
        int submitted = 0, received = 0;
        int maxInFlight = Math.max(threads * 4, 16);
        try {
            for (Path file : files) {
                completed.submit((Callable<FileOutcome>) () -> scan(file));
                submitted++;
                if (submitted - received >= maxInFlight) {
                    collect(completed.take(), results, errors);
                    received++;
                }
            }
            while (received < submitted) { collect(completed.take(), results, errors); received++; }
        } finally { pool.shutdownNow(); }
        results.sort(Comparator.comparing(r -> config.root().relativize(r.file()).toString()));
        long occurrences = results.stream().mapToLong(SearchResult::occurrences).sum();
        return new SearchReport(files.size(), results, occurrences, errors, System.nanoTime() - started);
    }

    private void collect(Future<FileOutcome> future, List<SearchResult> results, List<String> errors) throws InterruptedException {
        try {
            FileOutcome outcome = future.get();
            if (outcome.result != null) results.add(outcome.result);
            if (outcome.error != null) errors.add(outcome.error);
        } catch (java.util.concurrent.ExecutionException e) {
            errors.add("Unexpected error: " + e.getCause());
        }
    }

    private List<Path> discoverFiles() throws IOException {
        if (!Files.isDirectory(config.root())) throw new IOException("Not a valid directory: " + config.root());
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(config.root(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(config.root()) && config.excludedDirectories().contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && config.accepts(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
        });
        return files;
    }

    private FileOutcome scan(Path file) {
        List<SearchResult.LineMatch> matches = new ArrayList<>();
        long count = 0, lineNumber = 0;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(file), decoder))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    int found = countOccurrences(line);
                    if (found > 0) { matches.add(new SearchResult.LineMatch(lineNumber, line, found)); count += found; }
                }
            }
            return new FileOutcome(count == 0 ? null : new SearchResult(file, matches, count), null);
        } catch (IOException | SecurityException e) { return new FileOutcome(null, file + ": " + e.getMessage()); }
    }

    private int countOccurrences(String line) {
        Matcher matcher = pattern.matcher(line);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private record FileOutcome(SearchResult result, String error) { }
    public record SearchReport(int filesScanned, List<SearchResult> results, long occurrences, List<String> errors, long elapsedNanos) {
        public SearchReport { results = List.copyOf(results); errors = List.copyOf(errors); }
        public double elapsedSeconds() { return elapsedNanos / 1_000_000_000.0; }
    }
}
