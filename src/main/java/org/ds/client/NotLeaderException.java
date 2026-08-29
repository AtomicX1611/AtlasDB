package org.ds.client;

/**
 * Thrown by Node.propose() when a write is received by a non-leader node.
 * Contains a hint to the current leader so the client can redirect.
 */
public class NotLeaderException extends Exception {
    private final String currentLeaderId;

    public NotLeaderException(String currentLeaderId) {
        super("Not the leader. Current leader: " + (currentLeaderId != null ? currentLeaderId : "unknown"));
        this.currentLeaderId = currentLeaderId;
    }

    /** The node ID of the current known leader, or null if unknown. */
    public String getCurrentLeaderId() {
        return currentLeaderId;
    }
}
