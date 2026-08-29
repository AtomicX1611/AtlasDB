package org.ds.Replication.Node;

/**
 * Represents the three possible Raft consensus roles a node can occupy.
 * Transitions:
 *   FOLLOWER  ──timeout──▶  CANDIDATE  ──majority──▶  LEADER
 *   LEADER    ──higher term──▶  FOLLOWER
 *   CANDIDATE ──higher term──▶  FOLLOWER
 */
public enum NodeRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
