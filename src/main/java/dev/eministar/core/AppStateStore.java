package dev.eministar.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class AppStateStore {
    private final Path baseDir = Path.of(System.getProperty("user.home"), ".finderx");
    private final Path pinnedPath = baseDir.resolve("pinned.txt");
    private final Path recentPath = baseDir.resolve("recent.txt");
    private final Path usagePath = baseDir.resolve("usage.tsv");
    private final Path settingsPath = baseDir.resolve("settings.properties");

    public AppStateStore() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {
        }
    }

    public LinkedHashSet<Path> loadPinned() {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String line : readLines(pinnedPath)) {
            decodePath(line).ifPresent(out::add);
        }
        return out;
    }

    public List<Path> loadRecent() {
        List<Path> out = new ArrayList<>();
        for (String line : readLines(recentPath)) {
            decodePath(line).ifPresent(out::add);
        }
        return out;
    }

    public Map<String, Integer> loadUsageScores() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        for (String line : readLines(usagePath)) {
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) {
                continue;
            }
            try {
                String key = decode(parts[0]);
                int value = Integer.parseInt(parts[1]);
                out.put(key, value);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public boolean loadSmartRankingEnabled() {
        return Boolean.parseBoolean(loadSetting("smartRankingEnabled", "true"));
    }

    public String loadSelectedRoot() {
        return loadSetting("selectedRoot", "C:\\");
    }

    public boolean loadPreviewEnabled() {
        return Boolean.parseBoolean(loadSetting("previewEnabled", "false"));
    }

    public int loadMaxResults() {
        try {
            return Integer.parseInt(loadSetting("maxResults", "700"));
        } catch (Exception ignored) {
            return 700;
        }
    }

    public boolean loadDiscordPresenceEnabled() {
        return Boolean.parseBoolean(loadSetting("discordPresenceEnabled", "true"));
    }

    public void saveSelectedRoot(String root) {
        saveSetting("selectedRoot", root);
    }

    public void savePreviewEnabled(boolean enabled) {
        saveSetting("previewEnabled", Boolean.toString(enabled));
    }

    public void saveMaxResults(int value) {
        saveSetting("maxResults", Integer.toString(value));
    }

    public void saveDiscordPresenceEnabled(boolean enabled) {
        saveSetting("discordPresenceEnabled", Boolean.toString(enabled));
    }

    public void clearAllState() {
        try {
            Files.deleteIfExists(pinnedPath);
            Files.deleteIfExists(recentPath);
            Files.deleteIfExists(usagePath);
            Files.deleteIfExists(settingsPath);
        } catch (IOException ignored) {
        }
    }

    private String loadSetting(String key, String fallback) {
        Properties p = new Properties();
        if (!Files.exists(settingsPath)) {
            return fallback;
        }
        try (var reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException ignored) {
            return fallback;
        }
        return p.getProperty(key, fallback);
    }

    public void savePinned(LinkedHashSet<Path> pinned) {
        List<String> lines = new ArrayList<>();
        for (Path path : pinned) {
            lines.add(encode(path.toString()));
        }
        writeLines(pinnedPath, lines);
    }

    public void saveRecent(List<Path> recent) {
        List<String> lines = new ArrayList<>();
        for (Path path : recent) {
            lines.add(encode(path.toString()));
        }
        writeLines(recentPath, lines);
    }

    public void saveUsageScores(Map<String, Integer> scores) {
        List<String> lines = new ArrayList<>(scores.size());
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            lines.add(encode(entry.getKey()) + "\t" + entry.getValue());
        }
        writeLines(usagePath, lines);
    }

    public void saveSmartRankingEnabled(boolean enabled) {
        saveSetting("smartRankingEnabled", Boolean.toString(enabled));
    }

    private void saveSetting(String key, String value) {
        Properties p = new Properties();
        if (Files.exists(settingsPath)) {
            try (var reader = Files.newBufferedReader(settingsPath, StandardCharsets.UTF_8)) {
                p.load(reader);
            } catch (IOException ignored) {
            }
        }
        p.setProperty(key, value);
        try {
            Files.createDirectories(baseDir);
            try (var writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                p.store(writer, "FinderX settings");
            }
        } catch (IOException ignored) {
        }
    }

    private List<String> readLines(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private void writeLines(Path path, List<String> lines) {
        try {
            Files.createDirectories(baseDir);
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private java.util.Optional<Path> decodePath(String line) {
        try {
            return java.util.Optional.of(Path.of(decode(line)));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
