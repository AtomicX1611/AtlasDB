package org.ds.Replication;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.logging.Logger;

/**
 * Persists Raft's two durable state variables:
 *   currentTerm — must never decrease across restarts
 *   votedFor    — must not vote twice for different candidates in the same term
 *
 * Crash safety: writes go to a temp file then atomically rename to meta.bin.
 * This guarantees we never see a partial write on recovery.
 *
 * Format (meta.bin):
 *   [term: 4B][votedForLen: 4B][votedFor: votedForLen bytes (UTF-8)]
 *   votedForLen = 0 → null (haven't voted this term)
 */
public class RaftMetadataStore {
    private static final Logger logger = Logger.getLogger(RaftMetadataStore.class.getName());

    private final Path metaPath;

    public RaftMetadataStore(Path metaPath) throws IOException {
        this.metaPath = metaPath;
        Files.createDirectories(metaPath.getParent());
    }

    public record Metadata(int term, String votedFor) {}

    /** Atomically persist currentTerm and votedFor. Must complete before responding to RPCs. */
    public synchronized void save(int term, String votedFor) throws IOException {
        byte[] vfBytes = votedFor != null
            ? votedFor.getBytes(StandardCharsets.UTF_8) : new byte[0];

        ByteBuffer buf = ByteBuffer.allocate(8 + vfBytes.length);
        buf.putInt(term);
        buf.putInt(vfBytes.length);
        buf.put(vfBytes);
        buf.flip();

        Path tmp = metaPath.resolveSibling(metaPath.getFileName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            fos.getChannel().write(buf);
            fos.getFD().sync(); // full fsync including metadata
        }
        Files.move(tmp, metaPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /** Load metadata on startup. Returns (term=0, votedFor=null) if no metadata file exists. */
    public Metadata load() {
        if (!Files.exists(metaPath)) return new Metadata(0, null);
        try (DataInputStream dis = new DataInputStream(
                new FileInputStream(metaPath.toFile()))) {
            int term    = dis.readInt();
            int vfLen   = dis.readInt();
            String vf   = null;
            if (vfLen > 0) {
                byte[] vfBytes = new byte[vfLen];
                dis.readFully(vfBytes);
                vf = new String(vfBytes, StandardCharsets.UTF_8);
            }
            logger.info("[Meta] Loaded: term=" + term + " votedFor=" + vf);
            return new Metadata(term, vf);
        } catch (IOException e) {
            logger.warning("[Meta] Corrupt metadata file, starting fresh: " + e.getMessage());
            return new Metadata(0, null);
        }
    }
}
