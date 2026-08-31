package org.ds.storage.snapshot;

import java.io.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Serializes and deserializes the Raft state machine snapshot.
 *
 * Snapshot format (snapshot.bin):
 *   [snapshotIndex: 4B][snapshotTerm: 4B][numEntries: 4B]
 *   [keyLen: 4B][key: UTF-8][valLen: 4B][val: UTF-8]  × numEntries
 *
 * Why we need snapshots:
 *   Without snapshots the Raft WAL grows forever. After a snapshot is taken:
 *   1. WAL is truncated to entries after snapshotIndex.
 *   2. On crash recovery: load snapshot → apply WAL from snapshotIndex+1.
 *   3. Future: lagging followers receive snapshot via InstallSnapshot RPC
 *      instead of replaying thousands of log entries.
 *
 * Phase 3 hook: add InstallSnapshot RPC so leaders can send this file
 * to followers that are too far behind (nextIndex < matchIndex - maxGap).
 */
public class Snapshotter {
    private static final Logger logger = Logger.getLogger(Snapshotter.class.getName());

    private final Path snapshotPath;

    public Snapshotter(Path snapshotPath) throws IOException {
        this.snapshotPath = snapshotPath;
        Files.createDirectories(snapshotPath.getParent());
    }

    public boolean exists() {
        return Files.exists(snapshotPath);
    }

    /**
     * Write snapshot atomically (temp file + rename).
     * Called by Node after the applier advances lastApplied past a threshold.
     */
    public void save(SnapshotData snap) throws IOException {
        Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fos))) {

            Map<String, String> data = snap.data();
            dos.writeInt(snap.lastIndex());
            dos.writeInt(snap.lastTerm());
            dos.writeInt(data.size());

            for (Map.Entry<String, String> e : data.entrySet()) {
                byte[] kb = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] vb = e.getValue().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(kb.length);
                dos.write(kb);
                dos.writeInt(vb.length);
                dos.write(vb);
            }
            dos.flush();
            fos.getFD().sync();
        }

        Files.move(tmp, snapshotPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);

        logger.info("[Snapshot] Saved at index=" + snap.lastIndex()
            + " term=" + snap.lastTerm() + " entries=" + snap.data().size());
    }

    /** Load the latest snapshot from disk. */
    public SnapshotData load() throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(snapshotPath.toFile())))) {

            int lastIndex   = dis.readInt();
            int lastTerm    = dis.readInt();
            int numEntries  = dis.readInt();
            Map<String, String> data = new HashMap<>(numEntries * 2);

            for (int i = 0; i < numEntries; i++) {
                int kl = dis.readInt();
                byte[] kb = new byte[kl];
                dis.readFully(kb);
                int vl = dis.readInt();
                byte[] vb = new byte[vl];
                dis.readFully(vb);
                data.put(new String(kb, StandardCharsets.UTF_8),
                         new String(vb, StandardCharsets.UTF_8));
            }

            logger.info("[Snapshot] Loaded: index=" + lastIndex
                + " term=" + lastTerm + " entries=" + numEntries);
            return new SnapshotData(lastIndex, lastTerm, data);
        }
    }

    // ── Wire format helpers (for InstallSnapshot RPC) ─────────────────────────

    /** Serialize a snapshot to raw bytes for embedding in SnapshotRequest.data. */
    public byte[] serialize(SnapshotData snap) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(snap.lastIndex());
            dos.writeInt(snap.lastTerm());
            dos.writeInt(snap.data().size());
            for (Map.Entry<String, String> e : snap.data().entrySet()) {
                byte[] kb = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] vb = e.getValue().getBytes(StandardCharsets.UTF_8);
                dos.writeInt(kb.length); dos.write(kb);
                dos.writeInt(vb.length); dos.write(vb);
            }
        }
        return baos.toByteArray();
    }

    /** Deserialize raw bytes (from SnapshotRequest.data) into a SnapshotData record. */
    public SnapshotData deserialize(byte[] raw) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw))) {
            int lastIndex  = dis.readInt();
            int lastTerm   = dis.readInt();
            int n          = dis.readInt();
            Map<String, String> data = new HashMap<>(n * 2);
            for (int i = 0; i < n; i++) {
                int kl = dis.readInt(); byte[] kb = new byte[kl]; dis.readFully(kb);
                int vl = dis.readInt(); byte[] vb = new byte[vl]; dis.readFully(vb);
                data.put(new String(kb, StandardCharsets.UTF_8),
                         new String(vb, StandardCharsets.UTF_8));
            }
            return new SnapshotData(lastIndex, lastTerm, data);
        }
    }
}
