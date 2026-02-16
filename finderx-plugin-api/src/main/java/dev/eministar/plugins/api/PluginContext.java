package dev.eministar.plugins.api;

import java.nio.file.Path;

public interface PluginContext {
    Path appHome();

    Path activeRoot();

    String languageCode();

    default Path pluginDataDir(String pluginId) {
        return appHome().resolve("plugins-data").resolve(pluginId == null ? "unknown" : pluginId);
    }

    default PluginLogger logger(String pluginId) {
        return new PluginLogger() {
            @Override
            public void debug(String message) {
            }

            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message, Throwable throwable) {
            }
        };
    }

    default PluginSettings settings(String pluginId) {
        return new PluginSettings() {
            @Override
            public String getString(String key, String fallback) {
                return fallback;
            }

            @Override
            public int getInt(String key, int fallback) {
                return fallback;
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return fallback;
            }

            @Override
            public void putString(String key, String value) {
            }

            @Override
            public void putInt(String key, int value) {
            }

            @Override
            public void putBoolean(String key, boolean value) {
            }

            @Override
            public void remove(String key) {
            }

            @Override
            public void save() {
            }
        };
    }

    default void reportStatus(String pluginId, String message) {
    }
}
