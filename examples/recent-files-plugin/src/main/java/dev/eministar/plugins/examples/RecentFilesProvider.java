package dev.eministar.plugins.examples;

import dev.eministar.plugins.api.PluginContext;
import dev.eministar.plugins.api.SearchProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class RecentFilesProvider implements SearchProvider {
    @Override
    public String id() {
        return "recent-files";
    }

    @Override
    public String name() {
        return "Recent Files Provider";
    }

    @Override
    public List<Path> search(String query, int limit, PluginContext context) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        Path recentFile = context.appHome().resolve("recent.txt");
        if (!Files.exists(recentFile)) {
            return List.of();
        }

        String q = query.toLowerCase(Locale.ROOT);
        List<Path> hits = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(recentFile, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return List.of();
        }

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(line), StandardCharsets.UTF_8);
                Path path = Path.of(decoded);
                String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).contains(q)) {
                    hits.add(path);
                    if (hits.size() >= limit) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return hits;
    }
}
