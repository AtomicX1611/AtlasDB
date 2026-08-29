package org.ds.storage.lsm;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Logger;

/**
 * Immutable, sorted, on-disk key-value file (Sorted String Table).
 *
 * ─── File Layout ──────────────────────────────────────────────────────────
 *
 *   [  Data Section  ]  sorted key-value pairs (offset 0 → indexStart)
 *   [  Index Section ]  sparse index: every SPARSE_FACTOR-th key → file offset
 *   [  Bloom Section ]  serialized Bloom filter
 *   [  Footer        ]  16 bytes: indexStart(8) + bloomStart(8)
 *
 * Data entry encoding:
 *   [keyLen: 4B][key: UTF-8][valLen: 4B][val: UTF-8]
 *   (val may be MemTable.TOMBSTONE to represent a deletion)
 *
 * ─── Read Path ────────────────────────────────────────────────────────────
 * 1. Bloom filter: if key definitely absent, return null immediately (0 I/Os).
 * 2. Sparse index binary search: find largest indexed key ≤ target.
 * 3. Linear scan from that file offset until key found or exceeded.
 *
 * Phase 5: replace linear scan with binary search on block-aligned pages.
 */
public class SSTable {
    private static final Logger logger = Logger.getLogger(SSTable.class.getName());

    static final int SPARSE_FACTOR = 16;  // index every 16th entry
    static final int FOOTER_SIZE   = 16;  // 2 longs

    private final Path        filePath;
    private final BloomFilter bloomFilter;
    private final List<IndexEntry> sparseIndex;  // in-memory sparse index
    private final long        indexSectionStart; // absolute file offset

    private record IndexEntry(String key, long offset) {}

    private SSTable(Path path, BloomFilter bf, List<IndexEntry> idx, long indexStart) {
        this.filePath          = path;
        this.bloomFilter       = bf;
        this.sparseIndex       = idx;
        this.indexSectionStart = indexStart;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Flush (MemTable → SSTable file)
    // ─────────────────────────────────────────────────────────────────────────

    /** Flush sorted MemTable snapshot to a new SSTable. Returns the opened table. */
    public static SSTable flush(Path dir, ConcurrentSkipListMap<String, String> data, long tableId)
            throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(String.format("sst-%06d.sst", tableId));

        int entryCount = Math.max(data.size(), 1);
        BloomFilter bloom = new BloomFilter(entryCount, 0.01);
        List<IndexEntry> index = new ArrayList<>();

        // ── Build data section in memory ────────────────────────────────────
        ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
        DataOutputStream dataDos = new DataOutputStream(dataOut);

        long absOffset = 0; // absolute offset from start of file (= start of data section)
        int entryNo = 0;

        for (Map.Entry<String, String> e : data.entrySet()) {
            bloom.add(e.getKey());
            if (entryNo % SPARSE_FACTOR == 0) {
                index.add(new IndexEntry(e.getKey(), absOffset));
            }
            byte[] kb = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] vb = e.getValue().getBytes(StandardCharsets.UTF_8);
            dataDos.writeInt(kb.length);
            dataDos.write(kb);
            dataDos.writeInt(vb.length);
            dataDos.write(vb);
            absOffset += 4 + kb.length + 4 + vb.length;
            entryNo++;
        }
        dataDos.flush();
        byte[] dataBytes = dataOut.toByteArray();

        // ── Build index section ──────────────────────────────────────────────
        ByteArrayOutputStream idxOut = new ByteArrayOutputStream();
        DataOutputStream idxDos = new DataOutputStream(idxOut);
        idxDos.writeInt(index.size());
        for (IndexEntry ie : index) {
            byte[] kb = ie.key().getBytes(StandardCharsets.UTF_8);
            idxDos.writeInt(kb.length);
            idxDos.write(kb);
            idxDos.writeLong(ie.offset());
        }
        idxDos.flush();
        byte[] idxBytes = idxOut.toByteArray();

        // ── Build bloom section ──────────────────────────────────────────────
        ByteArrayOutputStream bloomOut = new ByteArrayOutputStream();
        DataOutputStream bloomDos = new DataOutputStream(bloomOut);
        byte[] bsBytes = bloom.getBits().toByteArray();
        bloomDos.writeInt(bloom.getNumBits());
        bloomDos.writeInt(bloom.getNumHashFunctions());
        bloomDos.writeInt(bsBytes.length);
        bloomDos.write(bsBytes);
        bloomDos.flush();
        byte[] bloomBytes = bloomOut.toByteArray();

        // ── Footer ───────────────────────────────────────────────────────────
        long indexStart = dataBytes.length;
        long bloomStart = indexStart + idxBytes.length;

        ByteBuffer footer = ByteBuffer.allocate(FOOTER_SIZE);
        footer.putLong(indexStart);
        footer.putLong(bloomStart);
        footer.flip();

        // ── Write everything to file ─────────────────────────────────────────
        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            fos.write(dataBytes);
            fos.write(idxBytes);
            fos.write(bloomBytes);
            fos.getChannel().write(footer);
            fos.getFD().sync();
        }

        logger.info("[SST] Flushed " + path.getFileName()
            + ": " + entryNo + " entries, " + dataBytes.length + " data bytes");

        return new SSTable(path, bloom, index, indexStart);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Open (load existing SSTable from disk)
    // ─────────────────────────────────────────────────────────────────────────

    public static SSTable open(Path path) throws IOException {
        long fileSize = Files.size(path);
        if (fileSize < FOOTER_SIZE)
            throw new IOException("SSTable too small to be valid: " + path);

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {

            // ── Read footer ──────────────────────────────────────────────────
            raf.seek(fileSize - FOOTER_SIZE);
            long indexStart = raf.readLong();
            long bloomStart = raf.readLong();

            // ── Read index section ───────────────────────────────────────────
            raf.seek(indexStart);
            int numEntries = raf.readInt();
            List<IndexEntry> index = new ArrayList<>(numEntries);
            for (int i = 0; i < numEntries; i++) {
                int kl = raf.readInt();
                byte[] kb = new byte[kl];
                raf.readFully(kb);
                long off = raf.readLong();
                index.add(new IndexEntry(new String(kb, StandardCharsets.UTF_8), off));
            }

            // ── Read bloom section ───────────────────────────────────────────
            raf.seek(bloomStart);
            int numBits      = raf.readInt();
            int numHashes    = raf.readInt();
            int bsLen        = raf.readInt();
            byte[] bsBytes   = new byte[bsLen];
            raf.readFully(bsBytes);
            BloomFilter bloom = new BloomFilter(BitSet.valueOf(bsBytes), numBits, numHashes);

            logger.fine("[SST] Opened " + path.getFileName()
                + " (" + numEntries * SPARSE_FACTOR + " ~entries)");
            return new SSTable(path, bloom, index, indexStart);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Point Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Look up key in this SSTable.
     *
     * @return the stored value (may be MemTable.TOMBSTONE), or null if not present.
     */
    public String get(String key) throws IOException {
        if (!bloomFilter.mightContain(key)) return null; // definite miss

        // Binary search sparse index: find largest indexed key ≤ target
        long startOffset = 0;
        int lo = 0, hi = sparseIndex.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = sparseIndex.get(mid).key().compareTo(key);
            if (cmp <= 0) {
                startOffset = sparseIndex.get(mid).offset();
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        // Linear scan from startOffset within the data section
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(startOffset);

            while (raf.getFilePointer() < indexSectionStart) {
                int kl = raf.readInt();
                if (kl <= 0 || kl > 1_048_576) break; // sanity check
                byte[] kb = new byte[kl];
                raf.readFully(kb);
                String k = new String(kb, StandardCharsets.UTF_8);

                int vl = raf.readInt();
                if (vl < 0 || vl > 16_777_216) break;
                byte[] vb = new byte[vl];
                raf.readFully(vb);
                String v = new String(vb, StandardCharsets.UTF_8);

                int cmp = k.compareTo(key);
                if (cmp == 0) return v;   // found (may be TOMBSTONE)
                if (cmp > 0) return null; // passed target key
            }
        }
        return null; // bloom was a false positive
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Full scan (for snapshot / compaction)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns all entries in this SSTable (including tombstones). */
    public Map<String, String> readAll() throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            while (raf.getFilePointer() < indexSectionStart) {
                int kl = raf.readInt();
                if (kl <= 0 || kl > 1_048_576) break;
                byte[] kb = new byte[kl];
                raf.readFully(kb);
                int vl = raf.readInt();
                if (vl < 0 || vl > 16_777_216) break;
                byte[] vb = new byte[vl];
                raf.readFully(vb);
                result.put(new String(kb, StandardCharsets.UTF_8),
                           new String(vb, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    public Path getFilePath() { return filePath; }

    @Override
    public String toString() {
        return "SSTable{" + filePath.getFileName() + ", idxEntries=" + sparseIndex.size() + "}";
    }
}
