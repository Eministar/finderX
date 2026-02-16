package dev.eministar.plugins;

import dev.eministar.plugins.api.PluginCapability;
import dev.eministar.plugins.api.PluginContext;
import dev.eministar.plugins.api.PluginDescriptor;
import dev.eministar.plugins.api.PluginLogger;
import dev.eministar.plugins.api.PluginSettings;
import dev.eministar.plugins.api.SearchProvider;
import dev.eministar.plugins.api.SearchRequest;
import dev.eministar.plugins.api.SearchResult;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public final class PluginManager {
    private final Path appHome = Path.of(System.getProperty("user.home"), ".finderx");
    private final Path pluginsDir = appHome.resolve("plugins");
    private final Path pluginDataDir = appHome.resolve("plugins-data");
    private final Path pluginStateFile = pluginsDir.resolve("plugins.properties");

    private final List<LoadedPlugin> loadedPlugins = new CopyOnWriteArrayList<>();
    private final List<URLClassLoader> classLoaders = new CopyOnWriteArrayList<>();
    private final Supplier<Path> activeRootSupplier;
    private final Supplier<String> languageCodeSupplier;
    private final Object lifecycleLock = new Object();

    public PluginManager(Supplier<Path> activeRootSupplier, Supplier<String> languageCodeSupplier) {
        this.activeRootSupplier = activeRootSupplier;
        this.languageCodeSupplier = languageCodeSupplier;
    }

    public void reload() {
        synchronized (lifecycleLock) {
            shutdownInternal();
            ensureDirs();
            Map<String, Boolean> enabledState = loadEnabledState();
            List<Path> jars = scanPluginJars();

            for (Path jar : jars) {
                try {
                    URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
                    classLoaders.add(loader);
                    ServiceLoader<SearchProvider> serviceLoader = ServiceLoader.load(SearchProvider.class, loader);

                    for (SearchProvider provider : serviceLoader) {
                        if (provider == null || provider.id() == null || provider.id().isBlank()) {
                            continue;
                        }

                        String id = provider.id().trim();
                        boolean enabled = enabledState.getOrDefault(id, true);
                        PluginContext context = buildContext(id);

                        LoadedPlugin loaded = new LoadedPlugin(provider, jar, enabled);
                        loadedPlugins.add(loaded);

                        try {
                            provider.onLoad(context);
                            if (enabled) {
                                provider.onEnable(context);
                            }
                        } catch (Exception ex) {
                            context.logger(id).error("Plugin load failed", ex);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public List<Path> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Path activeRoot = safeActiveRoot();
        String lang = safeLanguageCode();
        SearchRequest request = new SearchRequest(query, limit, activeRoot, lang, Map.of());

        List<SearchResult> ranked = searchDetailed(request);
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (SearchResult result : ranked) {
            if (result == null || result.path() == null) {
                continue;
            }
            out.add(result.path());
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    public List<SearchResult> searchDetailed(SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank() || request.limit() <= 0) {
            return List.of();
        }

        List<SearchResult> merged = new ArrayList<>();
        for (LoadedPlugin loaded : loadedPlugins) {
            if (!loaded.enabled()) {
                continue;
            }
            SearchProvider provider = loaded.provider();
            PluginContext context = buildContext(provider.id());
            try {
                List<SearchResult> hits = provider.search(request, context);
                if (hits != null && !hits.isEmpty()) {
                    for (SearchResult hit : hits) {
                        if (hit == null || hit.path() == null) {
                            continue;
                        }
                        merged.add(hit);
                        if (merged.size() >= request.limit() * 4) {
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                context.logger(provider.id()).error("Plugin search failed", ex);
            }
        }

        merged.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        if (merged.size() > request.limit()) {
            return List.copyOf(merged.subList(0, request.limit()));
        }
        return List.copyOf(merged);
    }

    public List<PluginDescriptor> descriptors() {
        List<PluginDescriptor> out = new ArrayList<>(loadedPlugins.size());
        for (LoadedPlugin loaded : loadedPlugins) {
            SearchProvider provider = loaded.provider();
            Set<PluginCapability> capabilities = provider.capabilities() == null
                    ? Set.of(PluginCapability.SEARCH_PATHS)
                    : Set.copyOf(provider.capabilities());
            out.add(new PluginDescriptor(
                    provider.id(),
                    provider.name(),
                    provider.version(),
                    provider.description(),
                    provider.author(),
                    provider.homepage(),
                    capabilities,
                    loaded.enabled(),
                    loaded.jarPath()
            ));
        }
        out.sort(Comparator.comparing(PluginDescriptor::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    public void setPluginEnabled(String pluginId, boolean enabled) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        synchronized (lifecycleLock) {
            for (int i = 0; i < loadedPlugins.size(); i++) {
                LoadedPlugin loaded = loadedPlugins.get(i);
                if (!loaded.provider().id().equals(pluginId)) {
                    continue;
                }
                PluginContext context = buildContext(pluginId);
                try {
                    if (enabled && !loaded.enabled()) {
                        loaded.provider().onEnable(context);
                    } else if (!enabled && loaded.enabled()) {
                        loaded.provider().onDisable(context);
                    }
                } catch (Exception ex) {
                    context.logger(pluginId).error("Plugin enable/disable failed", ex);
                }
                loadedPlugins.set(i, new LoadedPlugin(loaded.provider(), loaded.jarPath(), enabled));
                break;
            }
            saveEnabledState();
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            shutdownInternal();
        }
    }

    private void shutdownInternal() {
        for (LoadedPlugin loaded : loadedPlugins) {
            SearchProvider provider = loaded.provider();
            PluginContext context = buildContext(provider.id());
            try {
                if (loaded.enabled()) {
                    provider.onDisable(context);
                }
            } catch (Exception ex) {
                context.logger(provider.id()).error("Plugin disable failed", ex);
            }
            try {
                provider.onUnload();
            } catch (Exception ex) {
                context.logger(provider.id()).error("Plugin unload failed", ex);
            }
        }
        loadedPlugins.clear();

        for (URLClassLoader loader : classLoaders) {
            try {
                loader.close();
            } catch (Exception ignored) {
            }
        }
        classLoaders.clear();
    }

    private void ensureDirs() {
        try {
            Files.createDirectories(pluginsDir);
            Files.createDirectories(pluginDataDir);
        } catch (IOException ignored) {
        }
    }

    private List<Path> scanPluginJars() {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(pluginsDir)) {
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName() != null
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        } catch (IOException ignored) {
        }
        return jars;
    }

    private Map<String, Boolean> loadEnabledState() {
        Properties properties = new Properties();
        if (Files.exists(pluginStateFile)) {
            try (var reader = Files.newBufferedReader(pluginStateFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException ignored) {
            }
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("enabled.")) {
                String id = name.substring("enabled.".length());
                out.put(id, Boolean.parseBoolean(properties.getProperty(name, "true")));
            }
        }
        return out;
    }

    private void saveEnabledState() {
        Properties properties = new Properties();
        for (LoadedPlugin loaded : loadedPlugins) {
            properties.setProperty("enabled." + loaded.provider().id(), Boolean.toString(loaded.enabled()));
        }
        try {
            Files.createDirectories(pluginsDir);
            try (var writer = Files.newBufferedWriter(pluginStateFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "FinderX plugin state");
            }
        } catch (IOException ignored) {
        }
    }

    private PluginContext buildContext(String pluginId) {
        final String safeId = pluginId == null || pluginId.isBlank() ? "unknown" : pluginId.trim();
        final Path activeRoot = safeActiveRoot();
        final String lang = safeLanguageCode();
        final Path dataDir = pluginDataDir.resolve(safeId);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ignored) {
        }

        return new PluginContext() {
            @Override
            public Path appHome() {
                return appHome;
            }

            @Override
            public Path activeRoot() {
                return activeRoot;
            }

            @Override
            public String languageCode() {
                return lang;
            }

            @Override
            public Path pluginDataDir(String pluginId) {
                if (pluginId == null || pluginId.isBlank()) {
                    return dataDir;
                }
                return pluginDataDir.resolve(pluginId);
            }

            @Override
            public PluginLogger logger(String pluginId) {
                String actual = pluginId == null || pluginId.isBlank() ? safeId : pluginId;
                return new PluginLogger() {
                    @Override
                    public void debug(String message) {
                        System.out.println("[Plugin:" + actual + "][DEBUG] " + safe(message));
                    }

                    @Override
                    public void info(String message) {
                        System.out.println("[Plugin:" + actual + "][INFO] " + safe(message));
                    }

                    @Override
                    public void warn(String message) {
                        System.out.println("[Plugin:" + actual + "][WARN] " + safe(message));
                    }

                    @Override
                    public void error(String message, Throwable throwable) {
                        System.err.println("[Plugin:" + actual + "][ERROR] " + safe(message));
                        if (throwable != null) {
                            throwable.printStackTrace(System.err);
                        }
                    }
                };
            }

            @Override
            public PluginSettings settings(String pluginId) {
                String actual = pluginId == null || pluginId.isBlank() ? safeId : pluginId;
                Path file = pluginDataDir.resolve(actual).resolve("settings.properties");
                return new FilePluginSettings(file);
            }

            @Override
            public void reportStatus(String pluginId, String message) {
                String actual = pluginId == null || pluginId.isBlank() ? safeId : pluginId;
                System.out.println("[Plugin:" + actual + "][STATUS] " + safe(message));
            }
        };
    }

    private Path safeActiveRoot() {
        Path activeRoot = activeRootSupplier == null ? Path.of("C:\\") : activeRootSupplier.get();
        if (activeRoot == null) {
            return Path.of("C:\\");
        }
        return activeRoot;
    }

    private String safeLanguageCode() {
        String lang = languageCodeSupplier == null ? "en" : languageCodeSupplier.get();
        if (lang == null || lang.isBlank()) {
            return "en";
        }
        return lang;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record LoadedPlugin(
            SearchProvider provider,
            Path jarPath,
            boolean enabled
    ) {
    }

    private static final class FilePluginSettings implements PluginSettings {
        private final Path file;
        private final Properties properties = new Properties();

        private FilePluginSettings(Path file) {
            this.file = file;
            load();
        }

        private void load() {
            if (!Files.exists(file)) {
                return;
            }
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException ignored) {
            }
        }

        @Override
        public String getString(String key, String fallback) {
            if (key == null) {
                return fallback;
            }
            return properties.getProperty(key, fallback);
        }

        @Override
        public int getInt(String key, int fallback) {
            try {
                return Integer.parseInt(getString(key, Integer.toString(fallback)));
            } catch (Exception ignored) {
                return fallback;
            }
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            return Boolean.parseBoolean(getString(key, Boolean.toString(fallback)));
        }

        @Override
        public void putString(String key, String value) {
            if (key == null) {
                return;
            }
            properties.setProperty(key, value == null ? "" : value);
        }

        @Override
        public void putInt(String key, int value) {
            putString(key, Integer.toString(value));
        }

        @Override
        public void putBoolean(String key, boolean value) {
            putString(key, Boolean.toString(value));
        }

        @Override
        public void remove(String key) {
            if (key != null) {
                properties.remove(key);
            }
        }

        @Override
        public void save() {
            try {
                Files.createDirectories(file.getParent());
                try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    properties.store(writer, "FinderX plugin settings");
                }
            } catch (IOException ignored) {
            }
        }
    }
}
