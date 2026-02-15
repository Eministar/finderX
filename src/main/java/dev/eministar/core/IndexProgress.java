package dev.eministar.core;

public record IndexProgress(
        long filesIndexed,
        long directoriesIndexed,
        boolean running,
        String currentPath,
        String phase,
        long itemsPerSecond,
        int activeWorkers,
        int maxWorkers,
        int queueDepth,
        boolean autoTuneActive
) {
    public IndexProgress(
            long filesIndexed,
            long directoriesIndexed,
            boolean running,
            String currentPath,
            String phase
    ) {
        this(filesIndexed, directoriesIndexed, running, currentPath, phase, -1L, 0, 0, 0, false);
    }

    public static IndexProgress idle() {
        return new IndexProgress(0, 0, false, "", "idle", -1L, 0, 0, 0, false);
    }
}
