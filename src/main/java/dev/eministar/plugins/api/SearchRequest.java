package dev.eministar.plugins.api;

import java.nio.file.Path;
import java.util.Map;

public record SearchRequest(
        String query,
        int limit,
        Path activeRoot,
        String languageCode,
        Map<String, String> filters
) {
}
