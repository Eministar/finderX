package dev.eministar.i18n;

public enum AppLanguage {
    ENGLISH("en", "English", "/flags/us.svg"),
    GERMAN("de", "Deutsch", "/flags/de.svg"),
    TURKISH("tr", "Turkce", "/flags/tr.svg");

    private final String code;
    private final String displayName;
    private final String flagSvgPath;

    AppLanguage(String code, String displayName, String flagSvgPath) {
        this.code = code;
        this.displayName = displayName;
        this.flagSvgPath = flagSvgPath;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public String flagSvgPath() {
        return flagSvgPath;
    }

    public static AppLanguage fromCode(String code) {
        if (code == null || code.isBlank()) {
            return ENGLISH;
        }
        for (AppLanguage language : values()) {
            if (language.code.equalsIgnoreCase(code.trim())) {
                return language;
            }
        }
        return ENGLISH;
    }
}
