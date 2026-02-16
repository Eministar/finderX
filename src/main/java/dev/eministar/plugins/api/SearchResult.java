package dev.eministar.plugins.api;

import java.nio.file.Path;
import java.util.Map;

public record SearchResult(
        Path path,
        double score,
        String sourceId,
        String displayName,
        Map<String, String> metadata
) {
    public static SearchResult ofPath(Path path, String sourceId) {
        return new SearchResult(path, 0.5d, sourceId, null, Map.of());
    }
}
