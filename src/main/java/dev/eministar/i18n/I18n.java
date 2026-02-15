package dev.eministar.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class I18n {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path LANGS_DIR = Path.of(System.getProperty("user.home"), ".finderx", "langs");
    private static final List<String> BUNDLED_LANG_FILES = List.of(
            "en.json", "de.json", "tr.json", "es.json", "fr.json",
            "it.json", "pt.json", "nl.json", "pl.json", "ru.json",
            "uk.json", "ar.json", "ja.json", "ko.json", "zh.json"
    );

    private static volatile Map<String, EnumMap<I18nKey, String>> translationsByCode = Map.of();
    private static volatile List<LanguageOption> languageOptions = List.of();

    private I18n() {
    }

    static {
        initialize();
    }

    public static synchronized void initialize() {
        ensureLangsDir();
        ensureBundledLanguageFiles();
        reload();
    }

    public static synchronized void reload() {
        Map<String, LanguageFile> rawByCode = new LinkedHashMap<>();

        try (var stream = Files.list(LANGS_DIR)) {
            stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName() != null
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> parseLanguageFile(path).ifPresent(lang -> rawByCode.put(normalizeCode(lang.code()), lang)));
        } catch (IOException ignored) {
        }

        EnumMap<I18nKey, String> englishBase = buildEnglishBase(rawByCode.get("en"));
        Map<String, EnumMap<I18nKey, String>> builtTranslations = new LinkedHashMap<>();
        List<LanguageOption> builtOptions = new ArrayList<>();

        for (Map.Entry<String, LanguageFile> entry : rawByCode.entrySet()) {
            String code = entry.getKey();
            LanguageFile file = entry.getValue();

            EnumMap<I18nKey, String> pack = new EnumMap<>(englishBase);
            if (file.translations() != null) {
                for (Map.Entry<String, String> translation : file.translations().entrySet()) {
                    if (translation.getKey() == null) {
                        continue;
                    }
                    try {
                        I18nKey key = I18nKey.valueOf(translation.getKey().trim());
                        String value = translation.getValue();
                        if (value != null && !value.isBlank()) {
                            pack.put(key, value);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            builtTranslations.put(code, pack);
            builtOptions.add(new LanguageOption(
                    code,
                    safeText(file.displayName(), code.toUpperCase(Locale.ROOT)),
                    safeText(file.flagSvgPath(), "/flags/us.svg")
            ));
        }

        if (!builtTranslations.containsKey("en")) {
            builtTranslations.put("en", englishBase);
            builtOptions.add(new LanguageOption("en", "English", "/flags/us.svg"));
        }

        builtOptions.sort(Comparator.comparing(LanguageOption::displayName, String.CASE_INSENSITIVE_ORDER));
        translationsByCode = Map.copyOf(builtTranslations);
        languageOptions = List.copyOf(builtOptions);
    }

    public static List<LanguageOption> availableLanguages() {
        return languageOptions;
    }

    public static Optional<LanguageOption> findLanguageByCode(String code) {
        String wanted = normalizeCode(code);
        return languageOptions.stream().filter(l -> l.code().equals(wanted)).findFirst();
    }

    public static String resolveLanguageCode(String requested) {
        String normalized = normalizeCode(requested);
        if (translationsByCode.containsKey(normalized)) {
            return normalized;
        }
        return "en";
    }

    public static String tr(String languageCode, I18nKey key, Object... args) {
        String code = resolveLanguageCode(languageCode);
        Map<String, EnumMap<I18nKey, String>> packs = translationsByCode;

        EnumMap<I18nKey, String> selected = packs.get(code);
        EnumMap<I18nKey, String> english = packs.get("en");

        String pattern = null;
        if (selected != null) {
            pattern = selected.get(key);
        }
        if (pattern == null && english != null) {
            pattern = english.get(key);
        }
        if (pattern == null) {
            pattern = key.name();
        }

        if (args == null || args.length == 0) {
            return pattern;
        }
        return String.format(pattern, args);
    }

    private static Optional<LanguageFile> parseLanguageFile(Path path) {
        try {
            Map<String, Object> root = JSON.readValue(path.toFile(), new TypeReference<>() {
            });
            String code = root.get("code") instanceof String value ? value : "";
            if (code.isBlank()) {
                return Optional.empty();
            }
            String displayName = root.get("displayName") instanceof String value ? value : code;
            String flagSvgPath = root.get("flagSvgPath") instanceof String value ? value : "/flags/us.svg";

            Map<String, String> translations = new LinkedHashMap<>();
            Object transNode = root.get("translations");
            if (transNode instanceof Map<?, ?> mapNode) {
                for (Map.Entry<?, ?> entry : mapNode.entrySet()) {
                    if (entry.getKey() instanceof String k && entry.getValue() instanceof String v) {
                        translations.put(k, v);
                    }
                }
            }

            return Optional.of(new LanguageFile(normalizeCode(code), displayName, flagSvgPath, translations));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static EnumMap<I18nKey, String> buildEnglishBase(LanguageFile englishFile) {
        EnumMap<I18nKey, String> out = new EnumMap<>(I18nKey.class);
        for (I18nKey key : I18nKey.values()) {
            out.put(key, key.name());
        }
        if (englishFile == null || englishFile.translations() == null) {
            return out;
        }
        for (Map.Entry<String, String> translation : englishFile.translations().entrySet()) {
            try {
                I18nKey key = I18nKey.valueOf(translation.getKey().trim());
                if (translation.getValue() != null && !translation.getValue().isBlank()) {
                    out.put(key, translation.getValue());
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void ensureLangsDir() {
        try {
            Files.createDirectories(LANGS_DIR);
        } catch (IOException ignored) {
        }
    }

    private static void ensureBundledLanguageFiles() {
        ensureLangsDir();
        for (String fileName : BUNDLED_LANG_FILES) {
            Path target = LANGS_DIR.resolve(fileName);
            try (InputStream in = I18n.class.getResourceAsStream("/langs/" + fileName)) {
                if (in == null) {
                    continue;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "en";
        }
        return code.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private record LanguageFile(
            String code,
            String displayName,
            String flagSvgPath,
            Map<String, String> translations
    ) {
    }
}
