package org.ds.storage.lsm;

import java.util.BitSet;

/**
 * Space-efficient probabilistic set membership test.
 *
 * Used by SSTables to skip disk reads for keys that definitely aren't present.
 * False positive rate is tunable at construction time (default 1%).
 *
 * Implementation: Kirsch–Mitzenmacher double-hashing.
 *   hash_i(key) = hash1(key) + i * hash2(key)  mod numBits
 * This produces k independent hash functions from just two evaluations,
 * avoiding k separate hash function implementations.
 *
 * Optimal parameters:
 *   m (numBits)   = -n * ln(p) / (ln 2)^2
 *   k (numHashes) = (m/n) * ln 2
 * where n = expected entries, p = target false-positive rate.
 */
public class BloomFilter {

    private final BitSet bits;
    private final int    numBits;
    private final int    numHashFunctions;

    /** Create a new empty filter. */
    public BloomFilter(int expectedEntries, double falsePositiveRate) {
        int n = Math.max(expectedEntries, 1);
        double p = Math.max(falsePositiveRate, 1e-6);
        this.numBits         = (int) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
        this.numHashFunctions = Math.max(1, (int) Math.round((double) numBits / n * Math.log(2)));
        this.bits = new BitSet(numBits);
    }

    /** Deserialize a filter from its raw components (used by SSTable.open). */
    public BloomFilter(BitSet bits, int numBits, int numHashFunctions) {
        this.bits             = bits;
        this.numBits          = numBits;
        this.numHashFunctions = numHashFunctions;
    }

    public void add(String key) {
        long h1 = murmur(key, 0x9747b28c);
        long h2 = murmur(key, h1);
        for (int i = 0; i < numHashFunctions; i++) {
            bits.set((int)(Math.abs(h1 + (long) i * h2) % numBits));
        }
    }

    /** Returns false iff the key is DEFINITELY NOT in the set (no false negatives). */
    public boolean mightContain(String key) {
        long h1 = murmur(key, 0x9747b28c);
        long h2 = murmur(key, h1);
        for (int i = 0; i < numHashFunctions; i++) {
            if (!bits.get((int)(Math.abs(h1 + (long) i * h2) % numBits))) return false;
        }
        return true;
    }

    // ── Murmur64-inspired hash ──────────────────────────────────────────────

    private long murmur(String key, long seed) {
        long h = seed;
        for (char c : key.toCharArray()) {
            h ^= (long) c;
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= (h >>> 33);
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }

    // ── Serialization helpers ───────────────────────────────────────────────

    public BitSet getBits()          { return bits; }
    public int    getNumBits()       { return numBits; }
    public int    getNumHashFunctions() { return numHashFunctions; }
}
