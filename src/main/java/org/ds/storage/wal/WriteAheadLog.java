package org.ds.storage.wal;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * Append-only Write-Ahead Log for Raft log entries.
 *
 * Wire format per record:
 *   [MAGIC: 4B][CRC32: 4B][LEN: 4B][PAYLOAD: LEN bytes]
 *
 * PAYLOAD encoding:
 *   [index: 4B][term: 4B][cmdLen: 4B][command: cmdLen bytes (UTF-8)]
 *
 * Durability: each append calls FileDescriptor.sync() (fdatasync equivalent)
 * to ensure the OS buffer is flushed to the storage device before returning.
 * This satisfies Raft's requirement that log entries be durable before responding.
 *
 * Recovery: reads all records from the start, validates CRC32, stops at first
 * corruption or EOF (handles partial writes from a crash mid-append).
 */
public class WriteAheadLog implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(WriteAheadLog.class.getName());

    private static final int MAGIC = 0xA71A5DB1;

    private final Path walPath;
    private FileOutputStream fos;
    private DataOutputStream dos;

    public WriteAheadLog(Path walPath) throws IOException {
        this.walPath = walPath;
        Files.createDirectories(walPath.getParent());
        open();
    }

    private void open() throws IOException {
        this.fos = new FileOutputStream(walPath.toFile(), true); // append mode
        this.dos = new DataOutputStream(new BufferedOutputStream(fos, 64 * 1024));
    }

    /** Persist one log entry to disk. Blocks until storage device acknowledges. */
    public synchronized void append(WalEntry entry) throws IOException {
        byte[] payload = encode(entry);
        int crc = crc32(payload);

        dos.writeInt(MAGIC);
        dos.writeInt(crc);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
        fos.getFD().sync(); // durability: wait for fsync
    }

    /**
     * Replay the entire WAL. Stops at first CRC mismatch or truncated record
     * (handles crash mid-write safely — last partial record is discarded).
     */
    public List<WalEntry> recover() throws IOException {
        List<WalEntry> entries = new ArrayList<>();
        if (!Files.exists(walPath)) return entries;

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(walPath.toFile())))) {

            while (true) {
                int magic;
                try {
                    magic = dis.readInt();
                } catch (EOFException e) {
                    break; // clean end of file
                }
                if (magic != MAGIC) {
                    logger.warning("[WAL] Bad magic at recovery — stopping: expected "
                        + Integer.toHexString(MAGIC) + " got " + Integer.toHexString(magic));
                    break;
                }
                int storedCrc = dis.readInt();
                int len       = dis.readInt();
                if (len <= 0 || len > 64 * 1024 * 1024) {
                    logger.warning("[WAL] Implausible record length " + len + " — stopping");
                    break;
                }
                byte[] payload = new byte[len];
                int read = dis.read(payload);
                if (read != len) {
                    logger.warning("[WAL] Truncated record (" + read + "/" + len + ") — stopping");
                    break;
                }
                if (crc32(payload) != storedCrc) {
                    logger.warning("[WAL] CRC mismatch — stopping (partial write discarded)");
                    break;
                }
                entries.add(decode(payload));
            }
        }
        logger.info("[WAL] Recovered " + entries.size() + " entries from " + walPath.getFileName());
        return entries;
    }

    /**
     * Rewrite the WAL keeping only entries with index > snapshotIndex.
     * Called after a snapshot is taken to prevent unbounded WAL growth.
     * Uses atomic temp-file rename for crash safety.
     */
    public synchronized void truncateBefore(int snapshotIndex) throws IOException {
        List<WalEntry> toKeep = recover().stream()
            .filter(e -> e.index() > snapshotIndex)
            .toList();

        Path tmp = walPath.resolveSibling(walPath.getFileName() + ".tmp");

        // Write surviving entries to temp file
        try (FileOutputStream tmpFos = new FileOutputStream(tmp.toFile());
             DataOutputStream tmpDos = new DataOutputStream(new BufferedOutputStream(tmpFos))) {
            for (WalEntry e : toKeep) {
                byte[] payload = encode(e);
                tmpDos.writeInt(MAGIC);
                tmpDos.writeInt(crc32(payload));
                tmpDos.writeInt(payload.length);
                tmpDos.write(payload);
            }
            tmpDos.flush();
            tmpFos.getFD().sync();
        }

        // Close current, replace atomically, reopen
        dos.close();
        fos.close();
        Files.move(tmp, walPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        open();

        logger.info("[WAL] Truncated before index " + snapshotIndex
            + "; " + toKeep.size() + " entries remain");
    }

    @Override
    public synchronized void close() throws IOException {
        if (dos != null) {
            dos.flush();
            fos.getFD().sync();
            dos.close();
        }
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    private byte[] encode(WalEntry e) {
        byte[] cmd = e.command().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(12 + cmd.length);
        buf.putInt(e.index());
        buf.putInt(e.term());
        buf.putInt(cmd.length);
        buf.put(cmd);
        return buf.array();
    }

    private WalEntry decode(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int index  = buf.getInt();
        int term   = buf.getInt();
        int cmdLen = buf.getInt();
        byte[] cmd = new byte[cmdLen];
        buf.get(cmd);
        return new WalEntry(index, term, new String(cmd, StandardCharsets.UTF_8));
    }

    private int crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return (int) c.getValue();
    }
}
