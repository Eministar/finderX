package dev.eministar.core;

public record IndexProgress(
        long filesIndexed,
        long directoriesIndexed,
        boolean running,
        String currentPath,
        String phase
) {
    public static IndexProgress idle() {
        return new IndexProgress(0, 0, false, "", "idle");
    }
}
