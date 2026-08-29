package org.ds.storage.lsm;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/**
 * LSM-Tree storage engine backing the AtlasDB state machine.
 *
 * Write path:
 *   put/delete → MemTable (in-memory, sorted)
 *                 └─ when full (4 MB) → flush to immutable SSTable on disk
 *
 * Read path (newest-to-oldest wins):
 *   MemTable → SSTable[N-1] → ... → SSTable[0]
 *
 *   Each layer short-circuits on the first non-null hit. Tombstones propagate:
 *   if any layer returns TOMBSTONE for key k, subsequent older layers are skipped
 *   and null (= deleted) is returned to the caller.
 *
 * Phase 5 hook: add background compaction (size-tiered or leveled) to merge
 * SSTables, remove tombstones, and bound read amplification.
 *
 * Thread safety:
 *   - MemTable writes: single-threaded (applierScheduler in Node)
 *   - MemTable reads:  ConcurrentSkipListMap — lock-free concurrent reads
 *   - SSTable list:    CopyOnWriteArrayList — reads never block
 *   - Flush:           single daemon thread (flushExecutor)
 */
public class LSMEngine {
    private static final Logger logger = Logger.getLogger(LSMEngine.class.getName());

    private final Path dataDir;

    /** Mutable write buffer. Swapped atomically on flush. */
    private volatile MemTable activeMemTable = new MemTable();

    /** Immutable on-disk tables. Index 0 = oldest, N-1 = newest. */
    private final List<SSTable> sstables = new CopyOnWriteArrayList<>();

    /** Monotonically increasing SSTable ID for file naming. */
    private final AtomicLong nextTableId;

    /** Single-threaded; avoids concurrent flushes. */
    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsm-flush");
        t.setDaemon(true);
        return t;
    });

    public LSMEngine(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
        this.nextTableId = new AtomicLong(System.currentTimeMillis());
        recover();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Write API
    // ─────────────────────────────────────────────────────────────────────────

    public void put(String key, String value) {
        activeMemTable.put(key, value);
        maybeFlush();
    }

    public void delete(String key) {
        activeMemTable.delete(key);
        maybeFlush();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Read API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Read key with newest-layer-wins semantics.
     * Returns null if the key doesn't exist or was deleted.
     */
    public String get(String key) {
        // 1. Check active MemTable first
        String val = activeMemTable.get(key);
        if (val != null) {
            return MemTable.TOMBSTONE.equals(val) ? null : val;
        }

        // 2. Check SSTables newest → oldest
        List<SSTable> tables = sstables; // snapshot ref (CopyOnWriteArrayList)
        for (int i = tables.size() - 1; i >= 0; i--) {
            try {
                val = tables.get(i).get(key);
            } catch (IOException e) {
                logger.warning("[LSM] Error reading SSTable " + tables.get(i) + ": " + e.getMessage());
                continue;
            }
            if (val != null) {
                return MemTable.TOMBSTONE.equals(val) ? null : val;
            }
        }
        return null; // not found anywhere
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Snapshot (for Raft snapshotting)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a point-in-time view of all live (non-deleted) KV pairs.
     * Used by Snapshotter to serialize the state machine.
     */
    public Map<String, String> snapshot() {
        // Merge all layers: oldest first, newer layers overwrite
        TreeMap<String, String> merged = new TreeMap<>();

        // SSTable data (oldest → newest)
        for (SSTable sst : sstables) {
            try {
                for (Map.Entry<String, String> e : sst.readAll().entrySet()) {
                    if (MemTable.TOMBSTONE.equals(e.getValue())) {
                        merged.remove(e.getKey()); // tombstone erases older value
                    } else {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
            } catch (IOException e) {
                logger.warning("[LSM] Snapshot: failed to read SSTable " + sst + ": " + e.getMessage());
            }
        }

        // MemTable (newest — overwrites everything)
        for (Map.Entry<String, String> e : activeMemTable.getSnapshot().entrySet()) {
            if (MemTable.TOMBSTONE.equals(e.getValue())) {
                merged.remove(e.getKey());
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }

        return merged;
    }

    /**
     * Replace the engine's state from snapshot data.
     * Called when installing a snapshot from the leader on a lagging follower.
     * Clears all existing MemTable + SSTable data.
     */
    public synchronized void restore(Map<String, String> data) {
        // Drop all existing SSTables from disk
        for (SSTable sst : sstables) {
            try { Files.deleteIfExists(sst.getFilePath()); } catch (IOException ignored) {}
        }
        sstables.clear();

        // Load the restored data into a fresh MemTable
        // (will be flushed to SSTable shortly since it may exceed threshold)
        MemTable fresh = new MemTable();
        data.forEach(fresh::put);
        this.activeMemTable = fresh;
        maybeFlush(); // flush immediately if large
        logger.info("[LSM] State restored from snapshot (" + data.size() + " entries)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Startup Recovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * On startup: scan the data directory for existing SSTable files and
     * re-open them in order (by table ID embedded in filename).
     * This restores the read layer without re-reading from WAL.
     */
    private void recover() throws IOException {
        List<Path> sstFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.sst")) {
            for (Path p : stream) sstFiles.add(p);
        }
        sstFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path p : sstFiles) {
            try {
                SSTable sst = SSTable.open(p);
                sstables.add(sst);
                logger.info("[LSM] Recovered SSTable: " + p.getFileName());
            } catch (IOException e) {
                logger.warning("[LSM] Skipping corrupt SSTable " + p + ": " + e.getMessage());
            }
        }
        logger.info("[LSM] Recovery complete: " + sstables.size() + " SSTables loaded");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Flush
    // ─────────────────────────────────────────────────────────────────────────

    private void maybeFlush() {
        if (activeMemTable.shouldFlush()) {
            triggerFlush();
        }
    }

    private synchronized void triggerFlush() {
        if (!activeMemTable.shouldFlush()) return; // double-check under lock
        MemTable toFlush = activeMemTable;
        activeMemTable = new MemTable(); // atomic swap: new writes go here
        long tableId = nextTableId.getAndIncrement();

        flushExecutor.submit(() -> {
            try {
                SSTable sst = SSTable.flush(dataDir, toFlush.getSnapshot(), tableId);
                sstables.add(sst);
                logger.info("[LSM] Flush complete: " + sst + " ("
                    + toFlush.getSizeBytes() / 1024 + " KB)");
            } catch (IOException e) {
                logger.severe("[LSM] Flush FAILED for table-" + tableId + ": " + e.getMessage());
                // On failure: data is still in toFlush (already swapped out). 
                // Recovery: replay Raft WAL from last snapshot.
            }
        });
    }

    public void shutdown() {
        // Force-flush remaining MemTable entries
        if (!activeMemTable.isEmpty()) {
            try {
                SSTable sst = SSTable.flush(dataDir, activeMemTable.getSnapshot(),
                    nextTableId.getAndIncrement());
                sstables.add(sst);
                logger.info("[LSM] Final flush on shutdown: " + sst);
            } catch (IOException e) {
                logger.warning("[LSM] Final flush failed: " + e.getMessage());
            }
        }
        flushExecutor.shutdownNow();
    }

    public List<SSTable> getSSTables() { return Collections.unmodifiableList(sstables); }
    public MemTable getMemTable()       { return activeMemTable; }
}
