package dev.eministar.core;

import java.nio.file.Path;
import java.time.Instant;

public record FileRecord(
        int id,
        Path path,
        Path parent,
        String name,
        String nameLower,
        boolean directory,
        long size,
        long modifiedEpochMillis
) {
    public String extension() {
        if (directory) {
            return "Folder";
        }
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(idx + 1).toUpperCase() : "File";
    }

    public Instant modifiedInstant() {
        return Instant.ofEpochMilli(modifiedEpochMillis);
    }
}
