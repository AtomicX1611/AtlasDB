package org.ds.Replication.Replicator;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.Node.Node;
import org.ds.Replication.PeerChannelPool;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.*;
import org.ds.storage.snapshot.SnapshotData;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Per-node outbound RPC engine for Raft.
 *
 * Phase 3 additions:
 *  • nextIndex[]  — tracks the next log index to send to each follower (per leader epoch).
 *  • matchIndex[] — highest log index known to be replicated on each follower.
 *  • installSnapshot() — sends the leader's snapshot to a lagging follower when
 *    nextIndex[peer] has fallen below the leader's snapshotLastIndex (i.e. the log
 *    entries that follower needs were already compacted away).
 *
 * Design:
 *  • initializeLeaderState() is called by Node.promoteToLeader() to reset per-peer
 *    indices at the start of each leader epoch.
 *  • replicateEntryToPeer() checks whether the follower needs a snapshot or normal
 *    AppendEntries and dispatches accordingly.
 */
public class Replicator {
    private static final Logger logger = Logger.getLogger(Replicator.class.getName());

    private final PeerChannelPool channelPool = new PeerChannelPool();

    // Per-peer state (meaningful only while the owning node is LEADER)
    private final ConcurrentHashMap<String, AtomicInteger> nextIndex  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> matchIndex = new ConcurrentHashMap<>();

    private final ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "raft-worker");
        t.setDaemon(true);
        return t;
    });

    // ─────────────────────────────────────────────────────────────────────────
    //  Leader state initialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by Node.promoteToLeader().
     * Resets nextIndex for all peers to lastLogIndex+1 (optimistic assumption),
     * and matchIndex to -1 (nothing confirmed yet). Standard Raft §5.3.
     */
    public void initializeLeaderState(int lastLogIndex, List<NodeMetaData> peers) {
        peers.forEach(p -> {
            nextIndex .put(p.getId(), new AtomicInteger(lastLogIndex + 1));
            matchIndex.put(p.getId(), new AtomicInteger(-1));
        });
        logger.info("[Replicator] Leader state init: nextIndex=" + (lastLogIndex + 1)
            + " for " + peers.size() + " peers");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Vote requests
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    //  Heartbeats
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    //  Log Replication with Quorum Latch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Replicate log[entryIndex] to all followers in parallel and wait until
     * a majority have ACK'd. Returns true if quorum achieved within 5 seconds.
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
     * Send AppendEntries for log[entryIndex] to a single follower.
     *
     * Phase 3 logic:
     *  1. Check if this follower needs a snapshot (nextIndex fell below snapshotLastIndex).
     *  2. If yes → call installSnapshot() and report success/failure.
     *  3. If no  → normal AppendEntries with backtracking on rejection.
     *
     * nextIndex is decremented on rejection and advanced on success.
     */
    private boolean replicateEntryToPeer(Node leader, NodeMetaData peer, int entryIndex) {

        // ── Phase 3: snapshot check ───────────────────────────────────────────
        int ni = nextIndex.computeIfAbsent(peer.getId(),
            k -> new AtomicInteger(leader.getLastLogIndex() + 1)).get();

        if (ni <= leader.getSnapshotLastIndex() && leader.getSnapshotter().exists()) {
            logger.info("[Replicator] " + peer.getId() + " is too far behind "
                + "(nextIndex=" + ni + " <= snapshotLastIndex=" + leader.getSnapshotLastIndex()
                + ") — sending snapshot");
            boolean sent = installSnapshot(leader, peer);
            if (sent) {
                int snapIdx = leader.getSnapshotLastIndex();
                nextIndex.get(peer.getId()).set(snapIdx + 1);
                matchIndex.computeIfAbsent(peer.getId(), k -> new AtomicInteger(-1)).set(snapIdx);
                // After snapshot, follower may still need entries up to entryIndex
                // — fall through to normal replication below only if needed.
                if (snapIdx >= entryIndex) return true;
            } else {
                return false;
            }
        }

        // ── Normal AppendEntries with backtracking ────────────────────────────
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
                // Entry missing from leader log — try snapshot as fallback
                if (leader.getSnapshotter().exists()) {
                    logger.warning("[Replicator] Log entry " + (prevIndex + 1)
                        + " missing — falling back to snapshot for " + peer.getId());
                    return installSnapshot(leader, peer);
                }
                logger.warning("[Replicator] Log entry missing and no snapshot available for "
                    + peer.getId());
                return false;
            }

            try {
                ManagedChannel channel = channelPool.getOrCreate(peer.getHost(), peer.getPort());
                RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS);

                AppendResponse resp = stub.appendEntries(reqBuilder.build());

                if (resp.getSuccess()) {
                    // Advance nextIndex/matchIndex on success
                    nextIndex .computeIfAbsent(peer.getId(), k -> new AtomicInteger(0)).set(entryIndex + 1);
                    matchIndex.computeIfAbsent(peer.getId(), k -> new AtomicInteger(-1)).set(entryIndex);
                    logger.fine("Replicated log[" + entryIndex + "] → " + peer.getId());
                    return true;
                }

                if (resp.getTerm() > leader.getCurrentTerm()) {
                    logger.info("Peer " + peer.getId() + " has higher term "
                        + resp.getTerm() + " — stepping down");
                    leader.stepDown(resp.getTerm(), null);
                    return false;
                }

                // Follower rejected: back off prevIndex
                if (prevIndex > -1) {
                    prevIndex--;
                    nextIndex.computeIfAbsent(peer.getId(), k -> new AtomicInteger(0))
                        .set(prevIndex + 1);
                } else {
                    logger.warning("Cannot back off further for " + peer.getId()
                        + " — log inconsistency at index 0");
                    return false;
                }

            } catch (Exception e) {
                logger.fine("Replication attempt " + attempt + " to " + peer.getId()
                    + " failed: " + e.getMessage());
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

    // ─────────────────────────────────────────────────────────────────────────
    //  InstallSnapshot
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Send leader's current snapshot to a lagging follower via InstallSnapshot RPC.
     * Triggered automatically when nextIndex[peer] <= snapshotLastIndex.
     *
     * @return true if the follower successfully installed the snapshot.
     */
    private boolean installSnapshot(Node leader, NodeMetaData peer) {
        try {
            SnapshotData snap = leader.getSnapshotter().load();
            byte[] data       = leader.getSnapshotter().serialize(snap);

            SnapshotRequest req = SnapshotRequest.newBuilder()
                .setLeaderId(leader.getId())
                .setTerm(leader.getCurrentTerm())
                .setLastIncludedIndex(snap.lastIndex())
                .setLastIncludedTerm(snap.lastTerm())
                .setData(ByteString.copyFrom(data))
                .build();

            ManagedChannel channel = channelPool.getOrCreate(peer.getHost(), peer.getPort());
            RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS); // snapshots can be large

            SnapshotResponse resp = stub.installSnapshot(req);

            if (resp.getTerm() > leader.getCurrentTerm()) {
                leader.stepDown(resp.getTerm(), null);
                return false;
            }

            if (resp.getSuccess()) {
                logger.info("[Replicator] InstallSnapshot → " + peer.getId()
                    + " succeeded (index=" + snap.lastIndex() + ")");
            }
            return resp.getSuccess();

        } catch (IOException e) {
            logger.warning("[Replicator] InstallSnapshot snapshot read failed: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warning("[Replicator] InstallSnapshot RPC to " + peer.getId()
                + " failed: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    public void shutdown() {
        workerPool.shutdownNow();
        channelPool.shutdown();
    }
}
