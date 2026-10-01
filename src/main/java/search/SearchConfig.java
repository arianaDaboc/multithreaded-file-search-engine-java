package search;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

public record SearchConfig(Path root, String query, boolean caseSensitive, Set<String> extensions, Set<String> excludedDirectories, boolean regex) {
    public SearchConfig {
        root = root.toAbsolutePath().normalize();
        extensions = extensions.stream().map(s -> s.startsWith(".") ? s.toLowerCase() : "." + s.toLowerCase()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        excludedDirectories = Set.copyOf(excludedDirectories);
        if (query.isEmpty()) throw new IllegalArgumentException("Query cannot be empty.");
        if (regex) Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public SearchConfig(Path root, String query, boolean caseSensitive, Set<String> extensions) {
        this(root, query, caseSensitive, extensions, Set.of(), false);
    }

    public boolean accepts(Path path) {
        if (extensions.isEmpty()) return true;
        Path name = path.getFileName();
        if (name == null) return false;
        String filename = name.toString().toLowerCase();
        return extensions.stream().anyMatch(filename::endsWith);
    }
}
