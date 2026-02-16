package dev.eministar.plugins.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SearchProvider {
    String id();

    String name();

    default String version() {
        return "1.0.0";
    }

    default String description() {
        return "";
    }

    default String author() {
        return "";
    }

    default String homepage() {
        return "";
    }

    default Set<PluginCapability> capabilities() {
        return Set.of(PluginCapability.SEARCH_PATHS);
    }

    default void onLoad(PluginContext context) {
    }

    default void onEnable(PluginContext context) {
    }

    default void onDisable(PluginContext context) {
    }

    default void onSettingsChanged(PluginContext context) {
    }

    default List<SearchResult> search(SearchRequest request, PluginContext context) {
        List<Path> raw = search(request.query(), request.limit(), context);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<SearchResult> out = new ArrayList<>(raw.size());
        for (Path path : raw) {
            if (path == null) {
                continue;
            }
            out.add(new SearchResult(path, 0.5d, id(), null, Map.of()));
        }
        return out;
    }

    List<Path> search(String query, int limit, PluginContext context);

    default void onUnload() {
    }
}
