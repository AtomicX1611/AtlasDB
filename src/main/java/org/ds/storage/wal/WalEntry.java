package org.ds.storage.wal;

/** Immutable WAL record representing one Raft log entry persisted to disk. */
public record WalEntry(int index, int term, String command) {}
