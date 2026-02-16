package dev.eministar.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class IndexService {
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final int PARALLEL_BUILD_THRESHOLD = 500_000;
    private static final int LIVE_HARVEST_LIMIT = 100_000;
    private static final long AUTOTUNE_SAMPLE_NANOS = 900_000_000L;
    private static final int MIN_SCAN_WORKERS = 2;

    private final int scanWorkers = Math.max(MIN_SCAN_WORKERS, Math.min(12, Runtime.getRuntime().availableProcessors()));
    private final int buildWorkers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    private final int searchWorkers = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    private final ExecutorService coordinatorExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-coordinator");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(scanWorkers, r -> {
        Thread t = new Thread(r, "scan-worker");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(searchWorkers, r -> {
        Thread t = new Thread(r, "search-worker");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService buildExecutor = Executors.newFixedThreadPool(buildWorkers, r -> {
        Thread t = new Thread(r, "build-worker");
        t.setDaemon(true);
        return t;
    });

    private final Path cacheDir = Path.of(System.getProperty("user.home"), ".finderx");
    private final Path legacyCachePath = cacheDir.resolve("index-cache-v4.tsv.gz");

    private volatile List<FileRecord> records = List.of();
    private volatile Map<Path, List<Integer>> childrenByParent = Map.of();
    private volatile String[] namesLower = new String[0];
    private volatile Map<Character, int[]> firstCharBuckets = Map.of();
    private volatile int[] fullRangeIds = new int[0];
    private volatile ConcurrentLinkedQueue<TempRecord> liveHarvest = null;

    private final ConcurrentHashMap<String, Integer> usageScores = new ConcurrentHashMap<>();
    private volatile boolean smartRankingEnabled = true;
    private volatile IndexMode indexMode = IndexMode.AUTO;
    private volatile boolean incrementalOnStartup = true;

    private final AtomicBoolean indexing = new AtomicBoolean();
    private final AtomicLong queryGeneration = new AtomicLong();

    public CompletableFuture<Void> startIndex(Path root, Consumer<IndexProgress> progressConsumer) {
        if (!indexing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            long startNanos = System.nanoTime();
            AtomicLong files = new AtomicLong(0);
            AtomicLong dirs = new AtomicLong(0);
            List<TempRecord> harvested = null;

            try {
                if (indexMode != IndexMode.FULL) {
                    if (indexMode == IndexMode.AUTO && !incrementalOnStartup) {
                        if (loadCacheIfPresent(root, progressConsumer)) {
                            liveHarvest = null;
                            indexing.set(false);
                            return;
                        }
                    } else {
                        List<TempRecord> cached = loadCacheSnapshot(root, progressConsumer);
                        if (!cached.isEmpty()) {
                            harvested = incrementalFromCache(root, cached, files, dirs, progressConsumer);
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            if (harvested == null) {
                progressConsumer.accept(new IndexProgress(0, 0, true, root.toString(), "scan"));
                harvested = scanFileSystem(root, files, dirs, progressConsumer);
            }

            progressConsumer.accept(new IndexProgress(files.get(), dirs.get(), true, "building in-memory search", "build"));

            List<FileRecord> snapshot = buildSnapshot(harvested);
            saveCache(snapshot, root);
            liveHarvest = null;

            long totalMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000L);
            long items = files.get() + dirs.get();
            long perSec = items * 1000L / totalMs;
            progressConsumer.accept(new IndexProgress(files.get(), dirs.get(), false, "Index ready (" + perSec + " items/s)", "ready"));
            indexing.set(false);
        }, coordinatorExecutor);
    }

    public CompletableFuture<List<FileRecord>> searchAsync(String query, int limit) {
        long generation = queryGeneration.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                return List.of();
            }
            List<FileRecord> snapshot = records;
            String[] localNames = namesLower;
            if (snapshot.isEmpty() || localNames.length != snapshot.size()) {
                ConcurrentLinkedQueue<TempRecord> live = liveHarvest;
                if (indexing.get() && live != null) {
                    return searchLive(q, limit, generation, live);
                }
                return List.of();
            }

            List<FileRecord> exact = new ArrayList<>(16);
            List<FileRecord> prefix = new ArrayList<>(Math.min(limit, 128));
            List<FileRecord> contains = new ArrayList<>(Math.min(limit, 128));

            int[] preferredPool = selectPreferredPool(q, snapshot.size());
            for (int id : preferredPool) {
                if (generation != queryGeneration.get()) {
                    return List.of();
                }
                String name = localNames[id];
                if (name.equals(q)) {
                    exact.add(snapshot.get(id));
                } else if (name.startsWith(q)) {
                    prefix.add(snapshot.get(id));
                }
                if (exact.size() + prefix.size() >= limit) {
                    break;
                }
            }

            if (exact.size() + prefix.size() < limit) {
                for (int i = 0; i < localNames.length; i++) {
                    if (generation != queryGeneration.get()) {
                        return List.of();
                    }
                    String name = localNames[i];
                    if (name.equals(q) || name.startsWith(q)) {
                        continue;
                    }
                    if (name.contains(q)) {
                        contains.add(snapshot.get(i));
                        if (exact.size() + prefix.size() + contains.size() >= limit) {
                            break;
                        }
                    }
                }
            }

            List<FileRecord> merged = new ArrayList<>(Math.min(limit, exact.size() + prefix.size() + contains.size()));
            appendUntilLimit(merged, exact, limit);
            appendUntilLimit(merged, prefix, limit);
            appendUntilLimit(merged, contains, limit);
            if (smartRankingEnabled) {
                merged.sort((a, b) -> {
                    int byUsage = Integer.compare(usageScore(b.path()), usageScore(a.path()));
                    if (byUsage != 0) {
                        return byUsage;
                    }
                    return Integer.compare(a.path().toString().length(), b.path().toString().length());
                });
            }
            return merged;
        }, searchExecutor);
    }

    public List<FileRecord> listChildren(Path parent) {
        Map<Path, List<Integer>> childrenSnapshot = childrenByParent;
        List<FileRecord> recordSnapshot = records;
        List<Integer> ids = childrenSnapshot.get(parent);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<FileRecord> out = new ArrayList<>(ids.size());
        for (int id : ids) {
            if (id >= 0 && id < recordSnapshot.size()) {
                out.add(recordSnapshot.get(id));
            }
        }
        out.sort((a, b) -> {
            if (a.directory() != b.directory()) {
                return a.directory() ? -1 : 1;
            }
            return a.nameLower().compareTo(b.nameLower());
        });
        return out;
    }

    public void setSmartRankingEnabled(boolean enabled) {
        this.smartRankingEnabled = enabled;
    }

    public boolean isSmartRankingEnabled() {
        return smartRankingEnabled;
    }

    public void setIndexMode(IndexMode mode) {
        this.indexMode = mode == null ? IndexMode.AUTO : mode;
    }

    public IndexMode getIndexMode() {
        return indexMode;
    }

    public void setIncrementalOnStartup(boolean enabled) {
        this.incrementalOnStartup = enabled;
    }

    public boolean isIncrementalOnStartup() {
        return incrementalOnStartup;
    }

    public void setUsageScores(Map<String, Integer> scores) {
        usageScores.clear();
        usageScores.putAll(scores);
    }

    public Map<String, Integer> usageScoresSnapshot() {
        return Map.copyOf(usageScores);
    }

    public void recordOpen(Path path) {
        if (path == null) {
            return;
        }
        usageScores.merge(normalizePathKey(path), 1, Integer::sum);
    }

    public boolean isIndexing() {
        return indexing.get();
    }

    public void clearIndexCache() {
        try {
            Files.deleteIfExists(legacyCachePath);
            if (Files.isDirectory(cacheDir)) {
                try (var stream = Files.list(cacheDir)) {
                    stream.filter(path -> {
                                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                                return name.startsWith("index-cache-v5-") && name.endsWith(".tsv.gz");
                            })
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
        } catch (IOException ignored) {
        }
    }

    public void shutdown() {
        coordinatorExecutor.shutdownNow();
        scanExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        buildExecutor.shutdownNow();
    }

    private static void appendUntilLimit(List<FileRecord> out, List<FileRecord> source, int limit) {
        for (FileRecord fileRecord : source) {
            if (out.size() >= limit) {
                return;
            }
            out.add(fileRecord);
        }
    }

    private int[] selectPreferredPool(String query, int fallbackSize) {
        if (query.isEmpty()) {
            return ensureFullRange(fallbackSize);
        }
        char c = query.charAt(0);
        int[] pool = firstCharBuckets.get(Character.toLowerCase(c));
        return pool == null ? ensureFullRange(fallbackSize) : pool;
    }

    private int[] ensureFullRange(int size) {
        int[] local = fullRangeIds;
        if (local.length == size) {
            return local;
        }
        int[] rebuilt = new int[size];
        for (int i = 0; i < size; i++) {
            rebuilt[i] = i;
        }
        fullRangeIds = rebuilt;
        return rebuilt;
    }

    private List<TempRecord> scanFileSystem(
            Path root,
            AtomicLong files,
            AtomicLong dirs,
            Consumer<IndexProgress> progressConsumer
    ) {
        ConcurrentLinkedDeque<Path> directoryQueue = new ConcurrentLinkedDeque<>();
        ConcurrentLinkedQueue<TempRecord> liveQueue = new ConcurrentLinkedQueue<>();
        liveHarvest = liveQueue;
        AtomicLong liveHarvested = new AtomicLong(0);
        Set<String> visitedDirs = ConcurrentHashMap.newKeySet();
        AtomicLong activeWorkers = new AtomicLong(0);
        AtomicInteger allowedWorkers = new AtomicInteger(Math.min(scanWorkers, MIN_SCAN_WORKERS));
        AtomicLong latestItemsPerSecond = new AtomicLong(-1L);
        List<List<TempRecord>> workerHarvests = new ArrayList<>(scanWorkers);
        TempRecord rootRecord;

        try {
            rootRecord = toTempRecord(root, true);
            dirs.incrementAndGet();
            visitedDirs.add(normalizedDirKey(root));
            offerLiveRecord(rootRecord, liveQueue, liveHarvested);
            progressConsumer.accept(new IndexProgress(
                    files.get(),
                    dirs.get(),
                    true,
                    root.toString(),
                    "scan",
                    latestItemsPerSecond.get(),
                    allowedWorkers.get(),
                    scanWorkers,
                    directoryQueue.size(),
                    true
            ));
        } catch (Exception ignored) {
            return List.of();
        }

        directoryQueue.add(root);
        CountDownLatch done = new CountDownLatch(scanWorkers);
        for (int i = 0; i < scanWorkers; i++) {
            int workerId = i;
            List<TempRecord> localHarvest = new ArrayList<>(16_384);
            workerHarvests.add(localHarvest);
            scanExecutor.submit(() -> {
                try {
                    while (true) {
                        Path dir = null;
                        if (workerId < allowedWorkers.get()) {
                            dir = directoryQueue.pollFirst();
                        }
                        if (dir == null) {
                            if (directoryQueue.isEmpty() && activeWorkers.get() == 0) {
                                return;
                            }
                            LockSupport.parkNanos(300_000L);
                            continue;
                        }
                        activeWorkers.incrementAndGet();
                        try {
                            scanDirectory(
                                    dir,
                                    directoryQueue,
                                    localHarvest,
                                    liveQueue,
                                    liveHarvested,
                                    visitedDirs,
                                    files,
                                    dirs,
                                    allowedWorkers,
                                    latestItemsPerSecond,
                                    scanWorkers,
                                    progressConsumer
                            );
                        } finally {
                            activeWorkers.decrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        autoTuneScanWorkers(done, directoryQueue, activeWorkers, files, dirs, allowedWorkers, latestItemsPerSecond);

        int estimated = 1;
        for (List<TempRecord> local : workerHarvests) {
            estimated += local.size();
        }
        List<TempRecord> merged = new ArrayList<>(estimated);
        merged.add(rootRecord);
        for (List<TempRecord> local : workerHarvests) {
            merged.addAll(local);
        }
        return merged;
    }

    private List<TempRecord> incrementalFromCache(
            Path root,
            List<TempRecord> cached,
            AtomicLong files,
            AtomicLong dirs,
            Consumer<IndexProgress> progressConsumer
    ) {
        progressConsumer.accept(new IndexProgress(0, 0, true, root.toString(), "scan"));

        Map<String, TempRecord> cachedByPath = new HashMap<>(Math.max(1024, cached.size() * 2));
        Map<String, List<TempRecord>> cachedChildrenByParent = new HashMap<>(Math.max(1024, cached.size() / 4));
        for (TempRecord item : cached) {
            String key = normalizedDirKey(item.path());
            cachedByPath.put(key, item);
            if (item.parent() != null) {
                String parentKey = normalizedDirKey(item.parent());
                cachedChildrenByParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>(16)).add(item);
            }
        }

        ConcurrentLinkedQueue<TempRecord> liveQueue = new ConcurrentLinkedQueue<>();
        AtomicLong liveHarvested = new AtomicLong(0);
        liveHarvest = liveQueue;
        Set<String> visitedDirs = ConcurrentHashMap.newKeySet();
        List<TempRecord> merged = new ArrayList<>(Math.max(64_000, cached.size()));
        AtomicLong reused = new AtomicLong(0);
        AtomicLong changed = new AtomicLong(0);

        Deque<IncrementalTask> queue = new ArrayDeque<>();
        queue.addLast(new IncrementalTask(root, false));

        while (!queue.isEmpty()) {
            IncrementalTask task = queue.removeFirst();
            Path directory = task.directory();
            String dirKey = normalizedDirKey(directory);
            if (!visitedDirs.add(dirKey)) {
                continue;
            }

            TempRecord cachedDir = cachedByPath.get(dirKey);
            if (task.allowReuseCheck() && canReuseDirectory(directory, cachedDir)) {
                reuseSubtreeFromCache(
                        dirKey,
                        cachedByPath,
                        cachedChildrenByParent,
                        merged,
                        liveQueue,
                        liveHarvested,
                        files,
                        dirs,
                        reused
                );
                continue;
            }

            TempRecord currentDir;
            try {
                currentDir = toTempRecord(directory, true);
            } catch (Exception ignored) {
                continue;
            }
            appendRecord(currentDir, merged, liveQueue, liveHarvested, files, dirs);
            changed.incrementAndGet();

            try (var stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    try {
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            String childKey = normalizedDirKey(child);
                            TempRecord cachedChild = cachedByPath.get(childKey);
                            if (canReuseDirectory(child, cachedChild)) {
                                reuseSubtreeFromCache(
                                        childKey,
                                        cachedByPath,
                                        cachedChildrenByParent,
                                        merged,
                                        liveQueue,
                                        liveHarvested,
                                        files,
                                        dirs,
                                        reused
                                );
                            } else {
                                queue.addLast(new IncrementalTask(child, true));
                            }
                        } else {
                            TempRecord fileRecord = toTempRecord(child, false);
                            appendRecord(fileRecord, merged, liveQueue, liveHarvested, files, dirs);
                            changed.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }

        progressConsumer.accept(new IndexProgress(
                files.get(),
                dirs.get(),
                true,
                "incremental reused " + reused.get() + ", changed " + changed.get(),
                "build"
        ));
        return merged;
    }

    private static void appendRecord(
            TempRecord record,
            List<TempRecord> merged,
            ConcurrentLinkedQueue<TempRecord> liveQueue,
            AtomicLong liveHarvested,
            AtomicLong files,
            AtomicLong dirs
    ) {
        merged.add(record);
        offerLiveRecord(record, liveQueue, liveHarvested);
        if (record.directory()) {
            dirs.incrementAndGet();
        } else {
            files.incrementAndGet();
        }
    }

    private static boolean canReuseDirectory(Path directory, TempRecord cachedDirectoryRecord) {
        if (cachedDirectoryRecord == null || !cachedDirectoryRecord.directory()) {
            return false;
        }
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            long modified = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toMillis();
            return modified == cachedDirectoryRecord.modifiedEpochMillis();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void reuseSubtreeFromCache(
            String rootKey,
            Map<String, TempRecord> cachedByPath,
            Map<String, List<TempRecord>> cachedChildrenByParent,
            List<TempRecord> merged,
            ConcurrentLinkedQueue<TempRecord> liveQueue,
            AtomicLong liveHarvested,
            AtomicLong files,
            AtomicLong dirs,
            AtomicLong reused
    ) {
        Deque<String> queue = new ArrayDeque<>();
        queue.addLast(rootKey);
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            TempRecord record = cachedByPath.get(key);
            if (record == null) {
                continue;
            }
            appendRecord(record, merged, liveQueue, liveHarvested, files, dirs);
            reused.incrementAndGet();

            if (record.directory()) {
                List<TempRecord> children = cachedChildrenByParent.get(key);
                if (children != null) {
                    for (TempRecord child : children) {
                        queue.addLast(normalizedDirKey(child.path()));
                    }
                }
            }
        }
    }

    private static boolean sameRecord(TempRecord old, TempRecord current) {
        if (old.directory() != current.directory()) {
            return false;
        }
        if (!old.name().equals(current.name())) {
            return false;
        }
        if (old.modifiedEpochMillis() != current.modifiedEpochMillis()) {
            return false;
        }
        if (old.directory()) {
            return true;
        }
        return old.size() == current.size();
    }

    private void autoTuneScanWorkers(
            CountDownLatch done,
            ConcurrentLinkedDeque<Path> directoryQueue,
            AtomicLong activeWorkers,
            AtomicLong files,
            AtomicLong dirs,
            AtomicInteger allowedWorkers,
            AtomicLong latestItemsPerSecond
    ) {
        long bestRate = -1L;
        int bestWorkers = allowedWorkers.get();
        int declineWindows = 0;

        long prevTotal = files.get() + dirs.get();
        long prevSampleNanos = System.nanoTime();

        while (done.getCount() > 0) {
            LockSupport.parkNanos(AUTOTUNE_SAMPLE_NANOS);

            long now = System.nanoTime();
            long elapsed = Math.max(1L, now - prevSampleNanos);
            long total = files.get() + dirs.get();
            long delta = Math.max(0L, total - prevTotal);
            long rate = delta * 1_000_000_000L / elapsed;
            latestItemsPerSecond.set(rate);

            int currentAllowed = allowedWorkers.get();
            if (rate > bestRate) {
                bestRate = rate;
                bestWorkers = currentAllowed;
                declineWindows = 0;
            } else if (rate < (bestRate * 97L / 100L)) {
                declineWindows++;
            } else {
                declineWindows = 0;
            }

            int queueDepth = directoryQueue.size();
            long currentlyActive = activeWorkers.get();
            boolean saturated = queueDepth > currentAllowed * 8 && currentlyActive >= Math.max(1, currentAllowed - 1);

            if (declineWindows >= 2 && currentAllowed > MIN_SCAN_WORKERS) {
                allowedWorkers.set(Math.max(MIN_SCAN_WORKERS, bestWorkers));
                declineWindows = 0;
            } else if (saturated && currentAllowed < scanWorkers) {
                allowedWorkers.incrementAndGet();
            }

            prevTotal = total;
            prevSampleNanos = now;
        }

        try {
            done.await();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void scanDirectory(
            Path directory,
            ConcurrentLinkedDeque<Path> directoryQueue,
            List<TempRecord> localHarvest,
            ConcurrentLinkedQueue<TempRecord> liveQueue,
            AtomicLong liveHarvested,
            Set<String> visitedDirs,
            AtomicLong files,
            AtomicLong dirs,
            AtomicInteger allowedWorkers,
            AtomicLong latestItemsPerSecond,
            int maxWorkers,
            Consumer<IndexProgress> progressConsumer
    ) {
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                try {
                    boolean isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                    if (isDir) {
                        String key = normalizedDirKey(child);
                        if (!visitedDirs.add(key)) {
                            continue;
                        }
                        TempRecord record = toTempRecord(child, true);
                        localHarvest.add(record);
                        offerLiveRecord(record, liveQueue, liveHarvested);
                        directoryQueue.addLast(child);
                        long d = dirs.incrementAndGet();
                        if ((d & 1023L) == 0L) {
                            progressConsumer.accept(new IndexProgress(
                                    files.get(),
                                    d,
                                    true,
                                    child.toString(),
                                    "scan",
                                    latestItemsPerSecond.get(),
                                    allowedWorkers.get(),
                                    maxWorkers,
                                    directoryQueue.size(),
                                    true
                            ));
                        }
                    } else {
                        TempRecord record = toTempRecord(child, false);
                        localHarvest.add(record);
                        offerLiveRecord(record, liveQueue, liveHarvested);
                        long f = files.incrementAndGet();
                        if ((f & 4095L) == 0L) {
                            progressConsumer.accept(new IndexProgress(
                                    f,
                                    dirs.get(),
                                    true,
                                    child.toString(),
                                    "scan",
                                    latestItemsPerSecond.get(),
                                    allowedWorkers.get(),
                                    maxWorkers,
                                    directoryQueue.size(),
                                    true
                            ));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void offerLiveRecord(TempRecord record, ConcurrentLinkedQueue<TempRecord> liveQueue, AtomicLong liveHarvested) {
        while (true) {
            long current = liveHarvested.get();
            if (current >= LIVE_HARVEST_LIMIT) {
                return;
            }
            if (liveHarvested.compareAndSet(current, current + 1)) {
                liveQueue.offer(record);
                return;
            }
        }
    }

    private TempRecord toTempRecord(Path path, boolean directory) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        long size = 0L;
        long modifiedEpochMillis = 0L;
        try {
            modifiedEpochMillis = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (Exception ignored) {
        }
        if (!directory) {
            try {
                size = Files.size(path);
            } catch (Exception ignored) {
            }
        }
        return new TempRecord(
                path,
                path.getParent(),
                name,
                name.toLowerCase(Locale.ROOT),
                directory,
                size,
                modifiedEpochMillis
        );
    }

    private List<FileRecord> searchLive(String q, int limit, long generation, ConcurrentLinkedQueue<TempRecord> live) {
        List<FileRecord> exact = new ArrayList<>(16);
        List<FileRecord> prefix = new ArrayList<>(Math.min(limit, 128));
        List<FileRecord> contains = new ArrayList<>(Math.min(limit, 256));
        int id = 0;
        for (TempRecord t : live) {
            if (generation != queryGeneration.get()) {
                return List.of();
            }
            String name = t.nameLower();
            FileRecord fileRecord = new FileRecord(id, t.path(), t.parent(), t.name(), t.nameLower(), t.directory(), t.size(), t.modifiedEpochMillis());
            if (name.equals(q)) {
                exact.add(fileRecord);
            } else if (name.startsWith(q)) {
                prefix.add(fileRecord);
            } else if (name.contains(q)) {
                contains.add(fileRecord);
            }
            if (exact.size() + prefix.size() + contains.size() >= limit) {
                break;
            }
            id++;
        }
        List<FileRecord> merged = new ArrayList<>(Math.min(limit, exact.size() + prefix.size() + contains.size()));
        appendUntilLimit(merged, exact, limit);
        appendUntilLimit(merged, prefix, limit);
        appendUntilLimit(merged, contains, limit);
        if (smartRankingEnabled) {
            merged.sort((a, b) -> Integer.compare(usageScore(b.path()), usageScore(a.path())));
        }
        return merged;
    }

    private boolean loadCacheIfPresent(Path root, Consumer<IndexProgress> progressConsumer) {
        List<TempRecord> temp = loadCacheSnapshot(root, progressConsumer);
        if (temp.isEmpty()) {
            return false;
        }

        long files = temp.stream().filter(t -> !t.directory()).count();
        long dirs = temp.size() - files;
        buildSnapshot(temp);
        progressConsumer.accept(new IndexProgress(files, dirs, false, "Cache loaded instantly", "ready"));
        return true;
    }

    private List<TempRecord> loadCacheSnapshot(Path root, Consumer<IndexProgress> progressConsumer) {
        progressConsumer.accept(new IndexProgress(0, 0, true, "loading cached index", "cache"));
        Path rootCachePath = cachePathForRoot(root);

        List<TempRecord> perRoot = readCacheSnapshot(rootCachePath, root);
        if (!perRoot.isEmpty()) {
            return perRoot;
        }

        List<TempRecord> legacy = readCacheSnapshot(legacyCachePath, root);
        if (!legacy.isEmpty()) {
            writeCacheSnapshot(legacy, root);
            return legacy;
        }
        return List.of();
    }

    private void saveCache(List<FileRecord> snapshot, Path root) {
        if (snapshot.isEmpty()) {
            return;
        }
        List<TempRecord> asTemp = new ArrayList<>(snapshot.size());
        for (FileRecord r : snapshot) {
            asTemp.add(new TempRecord(
                    r.path(),
                    r.parent(),
                    r.name(),
                    r.nameLower(),
                    r.directory(),
                    r.size(),
                    r.modifiedEpochMillis()
            ));
        }
        writeCacheSnapshot(asTemp, root);
    }

    private List<TempRecord> readCacheSnapshot(Path path, Path expectedRoot) {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }

        List<TempRecord> temp = new ArrayList<>(250_000);
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8)
        )) {
            String first = reader.readLine();
            if (first == null || !first.startsWith("#root\t")) {
                return List.of();
            }
            String cachedRoot = decode(first.substring(6));
            if (!cachedRoot.equalsIgnoreCase(expectedRoot.toString())) {
                return List.of();
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\\t", 6);
                if (p.length < 6) {
                    continue;
                }
                Path recordPath = Path.of(decode(p[0]));
                String parentRaw = decode(p[1]);
                Path parent = parentRaw.isEmpty() ? null : Path.of(parentRaw);
                String name = decode(p[2]);
                String nameLower = name.toLowerCase(Locale.ROOT);
                boolean directory = "1".equals(p[3]);
                long size = Long.parseLong(p[4]);
                long modified = Long.parseLong(p[5]);
                temp.add(new TempRecord(recordPath, parent, name, nameLower, directory, size, modified));
            }
            return temp;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void writeCacheSnapshot(List<TempRecord> snapshot, Path root) {
        Path target = cachePathForRoot(root);
        try {
            Files.createDirectories(cacheDir);
            try (BufferedWriter writer = new BufferedWriter(
                    new java.io.OutputStreamWriter(
                            new GZIPOutputStream(
                                    Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                            ),
                            StandardCharsets.UTF_8
                    )
            )) {
                writer.write("#root\t");
                writer.write(encode(root.toString()));
                writer.newLine();

                for (TempRecord r : snapshot) {
                    writer.write(encode(r.path().toString()));
                    writer.write('\t');
                    writer.write(encode(r.parent() == null ? "" : r.parent().toString()));
                    writer.write('\t');
                    writer.write(encode(r.name()));
                    writer.write('\t');
                    writer.write(r.directory() ? "1" : "0");
                    writer.write('\t');
                    writer.write(Long.toString(r.size()));
                    writer.write('\t');
                    writer.write(Long.toString(r.modifiedEpochMillis()));
                    writer.newLine();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Path cachePathForRoot(Path root) {
        String normalizedRoot = normalizedDirKey(root);
        String encodedRoot = encode(normalizedRoot);
        return cacheDir.resolve("index-cache-v5-" + encodedRoot + ".tsv.gz");
    }

    private List<FileRecord> buildSnapshot(List<TempRecord> harvested) {
        if (harvested.size() >= PARALLEL_BUILD_THRESHOLD) {
            return buildSnapshotParallel(harvested);
        }

        List<FileRecord> builtRecords = new ArrayList<>(harvested.size());
        Map<Path, List<Integer>> builtChildrenByParent = new HashMap<>(Math.max(1024, harvested.size() / 8));
        String[] builtNamesLower = new String[harvested.size()];
        Map<Character, List<Integer>> charBuckets = new HashMap<>();

        for (int id = 0; id < harvested.size(); id++) {
            TempRecord item = harvested.get(id);
            FileRecord record = new FileRecord(
                    id,
                    item.path(),
                    item.parent(),
                    item.name(),
                    item.nameLower(),
                    item.directory(),
                    item.size(),
                    item.modifiedEpochMillis()
            );
            builtRecords.add(record);
            builtNamesLower[id] = item.nameLower();

            char bucketKey = item.nameLower().isEmpty() ? '#' : item.nameLower().charAt(0);
            charBuckets.computeIfAbsent(bucketKey, key -> new ArrayList<>(1024)).add(id);

            if (item.parent() != null) {
                builtChildrenByParent.computeIfAbsent(item.parent(), key -> new ArrayList<>(24)).add(id);
            }
        }

        Map<Character, int[]> packedBuckets = new HashMap<>(charBuckets.size());
        for (Map.Entry<Character, List<Integer>> e : charBuckets.entrySet()) {
            List<Integer> ids = e.getValue();
            int[] packed = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                packed[i] = ids.get(i);
            }
            packedBuckets.put(e.getKey(), packed);
        }

        this.records = Collections.unmodifiableList(builtRecords);
        this.childrenByParent = Map.copyOf(builtChildrenByParent);
        this.namesLower = builtNamesLower;
        this.firstCharBuckets = Map.copyOf(packedBuckets);
        this.fullRangeIds = ensureFullRange(builtRecords.size());
        return this.records;
    }

    private List<FileRecord> buildSnapshotParallel(List<TempRecord> harvested) {
        int size = harvested.size();
        FileRecord[] builtRecordArray = new FileRecord[size];
        String[] builtNamesLower = new String[size];

        int workers = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 16));
        int chunkSize = Math.max(8_192, (size + workers - 1) / workers);
        List<CompletableFuture<BuildChunk>> futures = new ArrayList<>();

        for (int start = 0; start < size; start += chunkSize) {
            int from = start;
            int to = Math.min(size, from + chunkSize);
            futures.add(CompletableFuture.supplyAsync(
                    () -> buildChunk(harvested, from, to, builtRecordArray, builtNamesLower),
                    buildExecutor
            ));
        }

        Map<Path, List<Integer>> builtChildrenByParent = new HashMap<>(Math.max(1024, size / 8));
        Map<Character, List<Integer>> charBuckets = new HashMap<>();

        for (CompletableFuture<BuildChunk> future : futures) {
            BuildChunk chunk = future.join();

            for (Map.Entry<Path, List<Integer>> entry : chunk.childrenByParent().entrySet()) {
                builtChildrenByParent.computeIfAbsent(entry.getKey(), key -> new ArrayList<>(entry.getValue().size()))
                        .addAll(entry.getValue());
            }

            for (Map.Entry<Character, List<Integer>> entry : chunk.charBuckets().entrySet()) {
                charBuckets.computeIfAbsent(entry.getKey(), key -> new ArrayList<>(entry.getValue().size()))
                        .addAll(entry.getValue());
            }
        }

        List<FileRecord> builtRecords = Collections.unmodifiableList(List.of(builtRecordArray));
        Map<Character, int[]> packedBuckets = packCharBuckets(charBuckets);

        this.records = builtRecords;
        this.childrenByParent = Map.copyOf(builtChildrenByParent);
        this.namesLower = builtNamesLower;
        this.firstCharBuckets = Map.copyOf(packedBuckets);
        this.fullRangeIds = ensureFullRange(builtRecords.size());
        return this.records;
    }

    private BuildChunk buildChunk(
            List<TempRecord> harvested,
            int fromInclusive,
            int toExclusive,
            FileRecord[] builtRecordArray,
            String[] builtNamesLower
    ) {
        Map<Path, List<Integer>> localChildrenByParent = new HashMap<>();
        Map<Character, List<Integer>> localCharBuckets = new HashMap<>();

        for (int id = fromInclusive; id < toExclusive; id++) {
            TempRecord item = harvested.get(id);
            FileRecord record = new FileRecord(
                    id,
                    item.path(),
                    item.parent(),
                    item.name(),
                    item.nameLower(),
                    item.directory(),
                    item.size(),
                    item.modifiedEpochMillis()
            );
            builtRecordArray[id] = record;
            builtNamesLower[id] = item.nameLower();

            char bucketKey = item.nameLower().isEmpty() ? '#' : item.nameLower().charAt(0);
            localCharBuckets.computeIfAbsent(bucketKey, key -> new ArrayList<>(256)).add(id);

            if (item.parent() != null) {
                localChildrenByParent.computeIfAbsent(item.parent(), key -> new ArrayList<>(24)).add(id);
            }
        }

        return new BuildChunk(localChildrenByParent, localCharBuckets);
    }

    private static Map<Character, int[]> packCharBuckets(Map<Character, List<Integer>> charBuckets) {
        Map<Character, int[]> packedBuckets = new HashMap<>(charBuckets.size());
        for (Map.Entry<Character, List<Integer>> e : charBuckets.entrySet()) {
            List<Integer> ids = e.getValue();
            int[] packed = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                packed[i] = ids.get(i);
            }
            packedBuckets.put(e.getKey(), packed);
        }
        return packedBuckets;
    }

    private int usageScore(Path path) {
        return usageScores.getOrDefault(normalizePathKey(path), 0);
    }

    private static String normalizedDirKey(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static String normalizePathKey(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static String encode(String value) {
        return B64_ENC.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(B64_DEC.decode(value), StandardCharsets.UTF_8);
    }

    private record TempRecord(
            Path path,
            Path parent,
            String name,
            String nameLower,
            boolean directory,
            long size,
            long modifiedEpochMillis
    ) {
    }

    private record BuildChunk(
            Map<Path, List<Integer>> childrenByParent,
            Map<Character, List<Integer>> charBuckets
    ) {
    }

    private record IncrementalTask(
            Path directory,
            boolean allowReuseCheck
    ) {
    }
}
