package dev.eministar.core;

public enum IndexMode {
    AUTO,
    INCREMENTAL,
    FULL;

    public static IndexMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return IndexMode.valueOf(value.trim().toUpperCase());
        } catch (Exception ignored) {
            return AUTO;
        }
    }
}
