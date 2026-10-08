package search;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        return search(threads, new SearchControl());
    }

    public SearchReport search(int threads, SearchControl control) throws IOException, InterruptedException {
        if (threads < 1) throw new IllegalArgumentException("Thread count must be at least 1.");
        if (control == null) throw new IllegalArgumentException("Search control cannot be null.");

        control.bindCoordinator(Thread.currentThread());
        List<Path> files;
        List<SearchError> errors = new ArrayList<>();
        try {
            files = discoverFiles(control, errors);
        } catch (InterruptedException e) {
            control.cancel();
            throw e;
        }
        control.reportProgress(files.size(), 0);

        long started = System.nanoTime();
        List<SearchResult> results = new ArrayList<>();
        MutableMetrics metrics = new MutableMetrics();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CompletionService<FileOutcome> completed = new ExecutorCompletionService<>(pool);
        List<Future<FileOutcome>> futures = new ArrayList<>();
        int submitted = 0;
        int received = 0;
        int maxInFlight = Math.max(threads * 4, 16);
        boolean interruptedExternally = false;

        try {
            for (Path file : files) {
                if (control.isCancellationRequested()) break;
                Future<FileOutcome> future = completed.submit((Callable<FileOutcome>) () -> scan(file));
                futures.add(future);
                submitted++;
                if (submitted - received >= maxInFlight) {
                    collect(completed.take(), results, errors, metrics);
                    received++;
                    control.reportProgress(files.size(), received);
                }
            }

            while (!control.isCancellationRequested() && received < submitted) {
                collect(completed.take(), results, errors, metrics);
                received++;
                control.reportProgress(files.size(), received);
            }
        } catch (InterruptedException e) {
            if (!control.isCancellationRequested()) {
                interruptedExternally = true;
                control.cancel();
            }
        } finally {
            boolean cancelled = control.isCancellationRequested();
            if (cancelled) {
                for (Future<FileOutcome> future : futures) future.cancel(true);
                pool.shutdownNow();
            } else {
                pool.shutdown();
            }
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                if (!control.isCancellationRequested()) interruptedExternally = true;
            }
            control.bindCoordinator(null);
        }

        if (interruptedExternally) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Search was interrupted.");
        }

        results.sort(Comparator.comparing(r -> config.root().relativize(r.file()).toString()));
        long occurrences = results.stream().mapToLong(SearchResult::occurrences).sum();
        return new SearchReport(received, results, occurrences, errors, System.nanoTime() - started,
                control.isCancellationRequested(), metrics.linesScanned, metrics.bytesScanned,
                metrics.totalFileProcessingNanos);
    }

    private void collect(Future<FileOutcome> future, List<SearchResult> results, List<SearchError> errors,
                         MutableMetrics metrics)
            throws InterruptedException {
        try {
            FileOutcome outcome = future.get();
            if (outcome.result() != null) results.add(outcome.result());
            if (outcome.error() != null) errors.add(outcome.error());
            metrics.linesScanned += outcome.linesScanned();
            metrics.bytesScanned += outcome.bytesRead();
            metrics.totalFileProcessingNanos += outcome.processingNanos();
        } catch (CancellationException ignored) {
            // A cancelled file task has no result to aggregate.
        } catch (ExecutionException e) {
            errors.add(new SearchError(config.root(), "Unexpected worker error: " + e.getCause()));
        }
    }

    private List<Path> discoverFiles(SearchControl control, List<SearchError> errors) throws IOException, InterruptedException {
        if (!Files.isDirectory(config.root())) throw new IOException("Not a valid directory: " + config.root());
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(config.root(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted() || control.isCancellationRequested()) return FileVisitResult.TERMINATE;
                if (!dir.equals(config.root()) && config.excludedDirectories().contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted() || control.isCancellationRequested()) return FileVisitResult.TERMINATE;
                if (attrs.isRegularFile() && config.accepts(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                errors.add(new SearchError(file, describeError(exc)));
                return FileVisitResult.CONTINUE;
            }
        });
        if (Thread.currentThread().isInterrupted() && !control.isCancellationRequested()) {
            throw new InterruptedException("Search was interrupted during file discovery.");
        }
        return files;
    }

    private FileOutcome scan(Path file) {
        List<SearchResult.LineMatch> matches = new ArrayList<>();
        long count = 0;
        long lineNumber = 0;
        long started = System.nanoTime();
        CountingInputStream input = null;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            input = new CountingInputStream(Files.newInputStream(file));
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(input, decoder))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                    lineNumber++;
                    int found = countOccurrences(line);
                    if (found > 0) {
                        matches.add(new SearchResult.LineMatch(lineNumber, line, found));
                        count += found;
                    }
                }
            }
            return new FileOutcome(count == 0 ? null : new SearchResult(file, matches, count), null,
                    lineNumber, input.bytesRead(), System.nanoTime() - started);
        } catch (CharacterCodingException e) {
            return new FileOutcome(null, new SearchError(file, "File is not valid UTF-8."),
                    lineNumber, bytesRead(input), System.nanoTime() - started);
        } catch (IOException | SecurityException e) {
            return new FileOutcome(null, new SearchError(file, describeError(e)),
                    lineNumber, bytesRead(input), System.nanoTime() - started);
        }
    }

    private long bytesRead(CountingInputStream input) {
        return input == null ? 0 : input.bytesRead();
    }

    static String describeError(Exception error) {
        if (error instanceof AccessDeniedException || error instanceof SecurityException) return "Access denied.";
        if (error instanceof NoSuchFileException) return "File disappeared before or during reading.";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private int countOccurrences(String line) {
        Matcher matcher = pattern.matcher(line);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private record FileOutcome(SearchResult result, SearchError error, long linesScanned,
                               long bytesRead, long processingNanos) { }

    private static final class MutableMetrics {
        private long linesScanned;
        private long bytesScanned;
        private long totalFileProcessingNanos;
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        private CountingInputStream(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }

        long bytesRead() { return bytesRead; }
    }

    public record SearchReport(int filesScanned, List<SearchResult> results, long occurrences,
                               List<SearchError> errors, long elapsedNanos, boolean cancelled,
                               long linesScanned, long bytesScanned, long totalFileProcessingNanos) {
        public SearchReport {
            results = List.copyOf(results);
            errors = List.copyOf(errors);
        }

        public SearchReport(int filesScanned, List<SearchResult> results, long occurrences,
                            List<SearchError> errors, long elapsedNanos) {
            this(filesScanned, results, occurrences, errors, elapsedNanos, false, 0, 0, 0);
        }

        public SearchReport(int filesScanned, List<SearchResult> results, long occurrences,
                            List<SearchError> errors, long elapsedNanos, boolean cancelled) {
            this(filesScanned, results, occurrences, errors, elapsedNanos, cancelled, 0, 0, 0);
        }

        public double elapsedSeconds() { return elapsedNanos / 1_000_000_000.0; }
        public double filesPerSecond() { return elapsedNanos == 0 ? 0 : filesScanned * 1_000_000_000.0 / elapsedNanos; }
        public double averageFileSeconds() {
            return filesScanned == 0 ? 0 : totalFileProcessingNanos / (double) filesScanned / 1_000_000_000.0;
        }
        public int errorCount() { return errors.size(); }
    }
}
