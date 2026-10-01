package search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchEngineTest {
    @TempDir Path temp;

    @Test void countsMatchesAndReportsLineNumbers() throws Exception {
        Files.writeString(temp.resolve("sample.txt"), "cat cat\ndog\ncat\n");
        var report = new SearchEngine(new SearchConfig(temp, "cat", true, Set.of("txt"))).search(2);
        assertEquals(1, report.results().size());
        assertEquals(3, report.occurrences());
        assertEquals(3, report.results().get(0).matches().get(1).lineNumber());
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
}
