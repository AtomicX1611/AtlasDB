package org.ds.storage.snapshot;

import java.util.Map;

/**
 * Immutable snapshot of Raft state machine state at a given log index.
 *
 * @param lastIndex the last log entry index included in this snapshot
 * @param lastTerm  the term of that entry
 * @param data      full KV store state (live keys only, no tombstones)
 */
public record SnapshotData(int lastIndex, int lastTerm, Map<String, String> data) {}
