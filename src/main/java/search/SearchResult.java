package search;

import java.nio.file.Path;
import java.util.List;

public record SearchResult(Path file, List<LineMatch> matches, long occurrences) {
    public SearchResult { matches = List.copyOf(matches); }
    public record LineMatch(long lineNumber, String text, int occurrences) { }
}
