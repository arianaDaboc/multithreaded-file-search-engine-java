package search;

import java.nio.file.Path;

/** A non-fatal problem encountered while discovering or reading a file. */
public record SearchError(Path file, String reason) { }
