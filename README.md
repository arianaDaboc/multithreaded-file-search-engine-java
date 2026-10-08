# Multithreaded File Search Engine

A Java application that searches text recursively through local files. It runs from the CLI or a JavaFX desktop interface, uses a fixed thread pool to scan files concurrently, and can save search history in SQLite.

## What it does

- Searches literal text or Java regular expressions, with optional case-insensitive matching.
- Filters by file extension and skips selected directories.
- Reports matching files, line numbers, occurrences, and scan statistics.
- Cancels an active search from the desktop UI or with `Ctrl+C` in the CLI. Completed results remain available; partial runs are not saved as completed history.
- Continues after unreadable files and reports the path and reason, including invalid UTF-8.
- Benchmarks worker counts and exports measurements as CSV.
- Saves completed searches and their matching lines to a local SQLite database.

## How it was built

1. **Recursive search:** added configuration and directory traversal, then implemented literal, regex, case, extension, and excluded-directory filters.
2. **Concurrency:** added a fixed `ExecutorService` pool with one file per task. A `CompletionService` collects completed tasks, while bounded submissions limit queued work.
3. **CLI and benchmark:** added command-line options, result summaries, worker-count comparisons, and CSV output.
4. **Persistence:** added a JDBC repository and SQLite tables for search runs, matched files, and matching lines. Writes use transactions; existing databases are upgraded with new metric columns when opened.
5. **Desktop app:** added a JavaFX interface that reuses the search engine and database repository. Searches run in the background so the window stays responsive.
6. **Reliability and measurements:** added cancellation, per-file error reporting, UTF-8 validation, progress updates, and performance metrics.

## Concurrency and error handling

Each worker reads one file and returns its own result. Workers do not write to a shared result list: the coordinating thread collects and sorts completed results. On cancellation, the engine stops submitting tasks, cancels outstanding futures, interrupts workers, and shuts down the pool.

Unreadable files do not stop the rest of the search. The report includes each affected path and reason. Invalid UTF-8 is reported instead of being silently replaced.

## Metrics

The CLI, desktop app, benchmark CSV, and saved history include:

- files and lines scanned, matching files, and occurrences;
- bytes actually read and elapsed search time;
- files per second, average worker processing time per file, and error count.

Directory discovery is excluded from elapsed search time. Average file processing is the mean worker time per completed file, so it can differ from wall-clock time when tasks run concurrently.

## Benchmark snapshot

The chart is an early baseline on a very small set of Java files, so it illustrates output rather than proving a performance gain. For a useful comparison, run the same larger dataset several times and compare medians.

![Initial benchmark timing chart](docs/benchmark.svg)

## Technologies

Java 17 · Maven · JavaFX · `ExecutorService` · `Callable`/`Future` · `CompletionService` · Java NIO · JDBC · SQLite · JUnit 5

## Project layout

```text
src/main/java/search/       Search engine, configuration, CLI, result and control models
src/main/java/search/db/    SQLite schema, repository, and history model
src/main/java/search/ui/    JavaFX desktop interface
src/test/java/search/       Search, cancellation, metrics, error, and database tests
docs/benchmark.svg          Initial benchmark chart
```

## Run and verify

Run the automated tests:

```powershell
mvn test
```

Open the desktop app:

```powershell
mvn javafx:run
```

Example CLI search:

```powershell
mvn exec:java "-Dexec.args=src SearchEngine --extensions java --threads 4"
```
