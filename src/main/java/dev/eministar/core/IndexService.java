package dev.eministar.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class IndexService {
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final int PARALLEL_BUILD_THRESHOLD = 200_000;

    private final int scanWorkers = Math.max(4, Math.min(24, Runtime.getRuntime().availableProcessors() * 2));
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

    private final Path cachePath = Path.of(System.getProperty("user.home"), ".finderx", "index-cache-v4.tsv.gz");

    private volatile List<FileRecord> records = List.of();
    private volatile Map<Path, List<Integer>> childrenByParent = Map.of();
    private volatile String[] namesLower = new String[0];
    private volatile Map<Character, int[]> firstCharBuckets = Map.of();
    private volatile int[] fullRangeIds = new int[0];
    private volatile ConcurrentLinkedQueue<TempRecord> liveHarvest = null;

    private final ConcurrentHashMap<String, Integer> usageScores = new ConcurrentHashMap<>();
    private volatile boolean smartRankingEnabled = true;

    private final AtomicBoolean indexing = new AtomicBoolean();
    private final AtomicLong queryGeneration = new AtomicLong();

    public CompletableFuture<Void> startIndex(Path root, Consumer<IndexProgress> progressConsumer) {
        if (!indexing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            long startNanos = System.nanoTime();
            try {
                if (loadCacheIfPresent(root, progressConsumer)) {
                    liveHarvest = null;
                    indexing.set(false);
                    return;
                }
            } catch (Exception ignored) {
            }

            AtomicLong files = new AtomicLong(0);
            AtomicLong dirs = new AtomicLong(0);
            progressConsumer.accept(new IndexProgress(0, 0, true, root.toString(), "scan"));

            List<TempRecord> harvested = scanFileSystem(root, files, dirs, progressConsumer);
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
            Files.deleteIfExists(cachePath);
        } catch (IOException ignored) {
        }
    }

    public void shutdown() {
        coordinatorExecutor.shutdownNow();
        scanExecutor.shutdownNow();
        searchExecutor.shutdownNow();
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
        ConcurrentLinkedQueue<TempRecord> harvested = new ConcurrentLinkedQueue<>();
        liveHarvest = harvested;
        Set<String> visitedDirs = ConcurrentHashMap.newKeySet();
        AtomicLong activeWorkers = new AtomicLong(0);

        try {
            harvested.add(toTempRecord(root, true, 0L, 0L));
            dirs.incrementAndGet();
            visitedDirs.add(normalizedDirKey(root));
        } catch (Exception ignored) {
            return List.of();
        }

        directoryQueue.add(root);
        CountDownLatch done = new CountDownLatch(scanWorkers);
        for (int i = 0; i < scanWorkers; i++) {
            scanExecutor.submit(() -> {
                try {
                    while (true) {
                        Path dir = directoryQueue.pollFirst();
                        if (dir == null) {
                            if (directoryQueue.isEmpty() && activeWorkers.get() == 0) {
                                return;
                            }
                            Thread.onSpinWait();
                            continue;
                        }
                        activeWorkers.incrementAndGet();
                        try {
                            scanDirectory(dir, directoryQueue, harvested, visitedDirs, files, dirs, progressConsumer);
                        } finally {
                            activeWorkers.decrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            done.await();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        return new ArrayList<>(harvested);
    }

    private void scanDirectory(
            Path directory,
            ConcurrentLinkedDeque<Path> directoryQueue,
            ConcurrentLinkedQueue<TempRecord> harvested,
            Set<String> visitedDirs,
            AtomicLong files,
            AtomicLong dirs,
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
                        harvested.add(toTempRecord(child, true, 0L, 0L));
                        directoryQueue.addLast(child);
                        long d = dirs.incrementAndGet();
                        if ((d & 1023L) == 0L) {
                            progressConsumer.accept(new IndexProgress(files.get(), d, true, child.toString(), "scan"));
                        }
                    } else {
                        harvested.add(toTempRecord(child, false, 0L, 0L));
                        long f = files.incrementAndGet();
                        if ((f & 4095L) == 0L) {
                            progressConsumer.accept(new IndexProgress(f, dirs.get(), true, child.toString(), "scan"));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private TempRecord toTempRecord(Path path, boolean directory, long size, long modifiedEpochMillis) {
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
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
        if (!Files.exists(cachePath)) {
            return false;
        }
        progressConsumer.accept(new IndexProgress(0, 0, true, "loading cached index", "cache"));

        List<TempRecord> temp = new ArrayList<>(250_000);
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new GZIPInputStream(Files.newInputStream(cachePath)), StandardCharsets.UTF_8)
        )) {
            String first = reader.readLine();
            if (first == null || !first.startsWith("#root\t")) {
                return false;
            }
            String cachedRoot = decode(first.substring(6));
            if (!cachedRoot.equalsIgnoreCase(root.toString())) {
                return false;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\\t", 6);
                if (p.length < 6) {
                    continue;
                }
                Path path = Path.of(decode(p[0]));
                String parentRaw = decode(p[1]);
                Path parent = parentRaw.isEmpty() ? null : Path.of(parentRaw);
                String name = decode(p[2]);
                String nameLower = name.toLowerCase(Locale.ROOT);
                boolean directory = "1".equals(p[3]);
                long size = Long.parseLong(p[4]);
                long modified = Long.parseLong(p[5]);
                temp.add(new TempRecord(path, parent, name, nameLower, directory, size, modified));
            }
        } catch (Exception e) {
            return false;
        }

        if (temp.isEmpty()) {
            return false;
        }

        long files = temp.stream().filter(t -> !t.directory()).count();
        long dirs = temp.size() - files;
        buildSnapshot(temp);
        progressConsumer.accept(new IndexProgress(files, dirs, false, "Cache loaded instantly", "ready"));
        return true;
    }

    private void saveCache(List<FileRecord> snapshot, Path root) {
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(cachePath.getParent());
            try (BufferedWriter writer = new BufferedWriter(
                    new java.io.OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(cachePath)), StandardCharsets.UTF_8)
            )) {
                writer.write("#root\t");
                writer.write(encode(root.toString()));
                writer.newLine();

                for (FileRecord r : snapshot) {
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
            futures.add(CompletableFuture.supplyAsync(() -> buildChunk(harvested, from, to, builtRecordArray, builtNamesLower)));
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
}
