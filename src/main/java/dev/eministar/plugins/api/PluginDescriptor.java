package dev.eministar.plugins.api;

import java.nio.file.Path;
import java.util.Set;

public record PluginDescriptor(
        String id,
        String name,
        String version,
        String description,
        String author,
        String homepage,
        Set<PluginCapability> capabilities,
        boolean enabled,
        Path jarPath
) {
}
