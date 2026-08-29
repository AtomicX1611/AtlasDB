package org.ds.Replication.Replicator;

import io.grpc.ManagedChannel;
import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.Node.Node;
import org.ds.Replication.PeerChannelPool;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class Replicator {
    private static final Logger logger = Logger.getLogger(Replicator.class.getName());

    private final PeerChannelPool channelPool = new PeerChannelPool();


    private final ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "raft-worker");
        t.setDaemon(true);
        return t;
    });

    public void submitVoteRequest(String candidateId, int term,
                                  int lastLogIndex, int lastLogTerm,
                                  NodeMetaData peer, Consumer<Boolean> callback) {
        workerPool.submit(() -> {
            boolean granted = sendVoteRequest(candidateId, term, lastLogIndex, lastLogTerm, peer);
            callback.accept(granted);
        });
    }

    private boolean sendVoteRequest(String candidateId, int term,
                                    int lastLogIndex, int lastLogTerm, NodeMetaData peer) {
        try {
            ManagedChannel channel = channelPool.getOrCreate(peer.getHost(), peer.getPort());
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(250, TimeUnit.MILLISECONDS);

            VoteRequest req = VoteRequest.newBuilder()
                .setCandidateId(candidateId)
                .setTerm(term)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

            VoteResponse resp = stub.requestVote(req);
            logger.fine("Vote response from " + peer.getId() + ": " + resp.getVoteGranted());
            return resp.getVoteGranted();
        } catch (Exception e) {
            logger.fine("Vote RPC to " + peer.getId() + " failed: " + e.getMessage());
            return false;
        }
    }

    public void sendHeartbeat(Node leader, NodeMetaData peer) {
        try {
            ManagedChannel channel = channelPool.getOrCreate(peer.getHost(), peer.getPort());
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(100, TimeUnit.MILLISECONDS);

            AppendRequest hb = AppendRequest.newBuilder()
                .setLeaderId(leader.getId())
                .setTerm(leader.getCurrentTerm())
                .setPrevLogIndex(leader.getLastLogIndex())
                .setPrevLogTerm(leader.getLastLogTerm())
                .setLeaderCommit(leader.getCommitIndex())
                .build();

            AppendResponse resp = stub.appendEntries(hb);

            if (!resp.getSuccess() && resp.getTerm() > leader.getCurrentTerm()) {
                logger.info("Heartbeat to " + peer.getId() + " revealed higher term "
                    + resp.getTerm() + " — leader stepping down");
                leader.stepDown(resp.getTerm(), null);
            }
        } catch (Exception e) {
            logger.fine("Heartbeat to " + peer.getId() + " failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Log Replication with Quorum Latch
    // ─────────────────────────────────────────────────────────────────

    /**
     * Replicate log[entryIndex] to all followers in parallel and wait until
     * a majority (excluding the leader, who already has it) have ACK'd.
     *
     * @return true if quorum is reached within 5 seconds, false otherwise.
     */
    public boolean replicateAndWaitForQuorum(Node leader, int entryIndex) {
        List<NodeMetaData> peers = leader.getPeers();

        if (peers.isEmpty()) return true;

        int majority       = (peers.size() + 1) / 2 + 1;
        int neededPeerAcks = majority - 1;

        CountDownLatch quorumLatch = new CountDownLatch(neededPeerAcks);

        for (NodeMetaData peer : peers) {
            workerPool.submit(() -> {
                if (replicateEntryToPeer(leader, peer, entryIndex)) {
                    quorumLatch.countDown();
                }
            });
        }

        try {
            return quorumLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Send AppendEntries for log[entryIndex] to a single follower,
     * retrying with backtracking if the follower's log is inconsistent.
     *
     * Backtracking: on each rejection, decrement prevIndex and include ALL
     * missing entries in the next attempt (not just the target entry).
     * This handles the case where a follower is multiple entries behind
     * without needing the nextIndex[] tracking from full Raft (Phase 2).
     *
     * @return true if successfully replicated; false on irrecoverable error.
     */
    private boolean replicateEntryToPeer(Node leader, NodeMetaData peer, int entryIndex) {
        int prevIndex = entryIndex - 1;

        for (int attempt = 0; attempt < 10; attempt++) {
           
            AppendRequest.Builder reqBuilder = AppendRequest.newBuilder()
                .setLeaderId(leader.getId())
                .setTerm(leader.getCurrentTerm())
                .setPrevLogIndex(prevIndex)
                .setPrevLogTerm(prevIndex >= 0 && leader.getLogEntry(prevIndex) != null
                    ? leader.getLogEntry(prevIndex).term : 0)
                .setLeaderCommit(leader.getCommitIndex());


            boolean anyNull = false;
            for (int i = prevIndex + 1; i <= entryIndex; i++) {
                LogEntry e = leader.getLogEntry(i);
                if (e == null) { anyNull = true; break; }
                reqBuilder.addEntries(
                    org.ds.proto.LogEntry.newBuilder()
                        .setIndex(e.index)
                        .setTerm(e.term)
                        .setCommand(e.getLog())
                        .build());
            }

            if (anyNull) {
                logger.warning("Log entry missing during replication to " + peer.getId());
                return false;
            }

            try {
                ManagedChannel channel = channelPool.getOrCreate(peer.getHost(), peer.getPort());
                RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS);

                AppendResponse resp = stub.appendEntries(reqBuilder.build());

                if (resp.getSuccess()) {
                    logger.fine("Replicated log[" + entryIndex + "] → " + peer.getId());
                    return true;
                }

           
                if (resp.getTerm() > leader.getCurrentTerm()) {
                    logger.info("Peer " + peer.getId() + " has higher term " + resp.getTerm() + " — stepping down");
                    leader.stepDown(resp.getTerm(), null);
                    return false;
                }

                
                if (prevIndex > -1) {
                    prevIndex--;
                } else {
                    logger.warning("Cannot back off further for " + peer.getId() + " — log inconsistency at index 0");
                    return false;
                }

            } catch (Exception e) {
                logger.fine("Replication attempt " + attempt + " to " + peer.getId() + " failed: " + e.getMessage());
               
                try {
                    Thread.sleep(Math.min(50L * (attempt + 1), 500));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        logger.warning("Replication to " + peer.getId() + " failed after 10 attempts");
        return false;
    }


    public void shutdown() {
        workerPool.shutdownNow();
        channelPool.shutdown();
    }
}
