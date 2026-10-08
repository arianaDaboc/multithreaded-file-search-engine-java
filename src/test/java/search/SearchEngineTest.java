package search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchEngineTest {
    @TempDir Path temp;

    @Test void countsMatchesAndReportsLineNumbers() throws Exception {
        Path sample = temp.resolve("sample.txt");
        Files.writeString(sample, "cat cat\ndog\ncat\n");
        var report = new SearchEngine(new SearchConfig(temp, "cat", true, Set.of("txt"))).search(2);
        assertEquals(1, report.results().size());
        assertEquals(3, report.occurrences());
        assertEquals(3, report.results().get(0).matches().get(1).lineNumber());
        assertEquals(3, report.linesScanned());
        assertEquals(Files.size(sample), report.bytesScanned());
        org.junit.jupiter.api.Assertions.assertTrue(report.filesPerSecond() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(report.averageFileSeconds() > 0);
        assertEquals(0, report.errorCount());
    }

    @Test void supportsCaseInsensitiveLiteralAndExtensionFilter() throws Exception {
        Files.writeString(temp.resolve("one.java"), "Hello hello");
        Files.writeString(temp.resolve("two.txt"), "hello");
        var report = new SearchEngine(new SearchConfig(temp, "HELLO", false, Set.of("java"))).search(1);
        assertEquals(2, report.occurrences());
    }

    @Test void supportsRegexAndExcludedDirectories() throws Exception {
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("kept.txt"), "error\nwarning\n");
        Files.writeString(temp.resolve("target/ignored.txt"), "error");
        var config = new SearchConfig(temp, "error$", true, Set.of(), Set.of("target"), true);
        var report = new SearchEngine(config).search(2);
        assertEquals(1, report.filesScanned());
        assertEquals(1, report.occurrences());
    }

    @Test void cancellationStopsAfterPartialProgressAndReturnsPartialResults() throws Exception {
        for (int i = 0; i < 200; i++) {
            Files.writeString(temp.resolve("file-" + i + ".txt"), "needle\n".repeat(100));
        }
        AtomicReference<SearchControl> controlRef = new AtomicReference<>();
        SearchControl control = new SearchControl(progress -> {
            if (progress.filesScanned() >= 1) controlRef.get().cancel();
        });
        controlRef.set(control);

        var report = new SearchEngine(new SearchConfig(temp, "needle", true, Set.of("txt")))
                .search(4, control);

        org.junit.jupiter.api.Assertions.assertTrue(report.cancelled());
        org.junit.jupiter.api.Assertions.assertTrue(report.filesScanned() >= 1);
        org.junit.jupiter.api.Assertions.assertTrue(report.filesScanned() < 200);
        org.junit.jupiter.api.Assertions.assertTrue(report.occurrences() > 0);
    }

    @Test void invalidUtf8IsReportedAndOtherFilesStillSearch() throws Exception {
        Files.write(temp.resolve("broken.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
        Files.writeString(temp.resolve("valid.txt"), "needle is still found\n");

        var report = new SearchEngine(new SearchConfig(temp, "needle", true, Set.of("txt"))).search(2);

        assertEquals(1, report.results().size());
        assertEquals(1, report.occurrences());
        assertEquals(1, report.errors().size());
        assertEquals(temp.resolve("broken.txt"), report.errors().get(0).file());
        assertEquals("File is not valid UTF-8.", report.errors().get(0).reason());
        assertEquals(1, report.linesScanned());
        org.junit.jupiter.api.Assertions.assertTrue(report.bytesScanned() >= Files.size(temp.resolve("valid.txt")));
    }

    @Test void givesUsefulReasonsForPermissionAndDisappearingFiles() {
        assertEquals("Access denied.", SearchEngine.describeError(new AccessDeniedException("private.txt")));
        assertEquals("File disappeared before or during reading.",
                SearchEngine.describeError(new NoSuchFileException("vanished.txt")));
    }
}
