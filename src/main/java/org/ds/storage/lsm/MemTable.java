package org.ds.storage.lsm;

import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory write buffer for the LSM-tree.
 * ConcurrentSkipListMap gives O(log n) ordered writes and thread-safe reads.
 * When sizeBytes exceeds FLUSH_THRESHOLD, the engine freezes this table and
 * flushes it to an immutable SSTable on disk.
 */
public class MemTable {

    public static final String TOMBSTONE = "\u0000__DEL__\u0000"; // sentinel for deleted keys

    private static final long FLUSH_THRESHOLD_BYTES = 4 * 1024 * 1024L; // 4 MB

    private final ConcurrentSkipListMap<String, String> data = new ConcurrentSkipListMap<>();
    private final AtomicLong sizeBytes = new AtomicLong(0);

    public void put(String key, String value) {
        data.put(key, value);
        sizeBytes.addAndGet(key.length() + value.length());
    }

    public void delete(String key) {
        data.put(key, TOMBSTONE);
        sizeBytes.addAndGet(key.length() + TOMBSTONE.length());
    }

    public String get(String key) {
        return data.get(key); // null = not present, TOMBSTONE = deleted
    }

    public boolean shouldFlush() {
        return sizeBytes.get() >= FLUSH_THRESHOLD_BYTES;
    }

    public long getSizeBytes() { return sizeBytes.get(); }

    public boolean isEmpty() { return data.isEmpty(); }

    /** Returns an ordered snapshot for flushing (sorted for SSTable write). */
    public ConcurrentSkipListMap<String, String> getSnapshot() {
        return new ConcurrentSkipListMap<>(data);
    }
}
