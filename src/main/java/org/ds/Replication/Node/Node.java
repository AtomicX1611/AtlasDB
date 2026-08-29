package org.ds.Replication.Node;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.HeartbeatSender;
import org.ds.Replication.RaftElectionTimer;
import org.ds.Replication.RaftMetadataStore;
import org.ds.Replication.RaftServiceImpl;
import org.ds.Replication.Replicator.Replicator;
import org.ds.Replication.utils.LogEntry;
import org.ds.client.AtlasServiceImpl;
import org.ds.client.NotLeaderException;
import org.ds.storage.StateMachine;
import org.ds.storage.snapshot.SnapshotData;
import org.ds.storage.snapshot.Snapshotter;
import org.ds.storage.wal.WalEntry;
import org.ds.storage.wal.WriteAheadLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Node {
    private static final Logger logger = Logger.getLogger(Node.class.getName());

    // Trigger a snapshot every SNAPSHOT_INTERVAL committed entries
    private static final int SNAPSHOT_INTERVAL = 50;

    // ── Identity ──────────────────────────────────────────────────────────────
    private final String id;
    private final String host;
    private final int    port;
    private final Map<String, NodeMetaData> clusterMembers;

    // ── Raft persistent state (mutations under synchronized(this)) ───────────
    private volatile int    currentTerm = 0;
    private volatile String votedFor    = null;

    // ── Raft volatile state (mutations under synchronized(this)) ────────────
    private volatile NodeRole role            = NodeRole.FOLLOWER;
    private volatile String   currentLeaderId = null;
    private final List<LogEntry> raftLog      = new ArrayList<>();

    // ── Commit / apply pointers ──────────────────────────────────────────────
    private volatile int commitIndex = -1;
    private volatile int lastApplied = -1;

    // ── Infrastructure ────────────────────────────────────────────────────────
    private volatile boolean isActive = true;
    private Server           grpcServer;
    private Replicator       replicator;
    private RaftElectionTimer  electionTimer;
    private HeartbeatSender    heartbeatSender;

    // ── Persistence (Phase 2) ─────────────────────────────────────────────────
    private final WriteAheadLog      raftWal;
    private final RaftMetadataStore  metaStore;
    private final Snapshotter        snapshotter;

    // ── State machine & applier ───────────────────────────────────────────────
    private final StateMachine stateMachine;
    private ScheduledExecutorService applierScheduler;


    public Node(String id, String host, int port, Map<String, NodeMetaData> clusterMembers) {
        this.id             = id;
        this.host           = host;
        this.port           = port;
        this.clusterMembers = clusterMembers;

        var dataDir = Paths.get("data", id);

        try {
            Files.createDirectories(dataDir);
            this.stateMachine = new StateMachine(dataDir.resolve("lsm"));
            this.raftWal      = new WriteAheadLog(dataDir.resolve("raft.wal"));
            this.metaStore    = new RaftMetadataStore(dataDir.resolve("meta.bin"));
            this.snapshotter  = new Snapshotter(dataDir.resolve("snapshot.bin"));
        } catch (IOException e) {
            throw new RuntimeException("[" + id + "] Failed to initialize persistence layer", e);
        }

        this.applierScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "applier-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    public void init(Replicator replicator) {
        this.replicator    = replicator;
        recoverFromDisk();   
        this.electionTimer   = new RaftElectionTimer(this);
        this.heartbeatSender = new HeartbeatSender(this, replicator);
        startApplierLoop();
        electionTimer.start();
        logger.info("[" + id + "] Initialized (term=" + currentTerm
            + " log=" + raftLog.size() + " entries) — election timer armed");
    }

    /**
     */
    private void recoverFromDisk() {
        try {
            // Step 1: metadata
            RaftMetadataStore.Metadata meta = metaStore.load();
            this.currentTerm = meta.term();
            this.votedFor    = meta.votedFor();

            // Step 2: snapshot
            if (snapshotter.exists()) {
                SnapshotData snap = snapshotter.load();
                stateMachine.restore(snap.data());
                this.commitIndex = snap.lastIndex();
                this.lastApplied = snap.lastIndex();
                logger.info("[" + id + "] Snapshot restored: index=" + snap.lastIndex());
            }

            // Step 3: WAL replay (only entries after the snapshot)
            List<WalEntry> walEntries = raftWal.recover();
            int snapshotLastIndex = commitIndex; // -1 if no snapshot

            synchronized (this) {
                for (WalEntry we : walEntries) {
                    if (we.index() > snapshotLastIndex) {
                        raftLog.add(new LogEntry(we.index(), we.term(), we.command()));
                    }
                }
            }



            logger.info("[" + id + "] Recovery done: term=" + currentTerm
                + " log=" + raftLog.size() + " lastApplied=" + lastApplied);
        } catch (IOException e) {
            logger.warning("[" + id + "] Recovery failed — starting fresh: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Raft §5.2 — Leader Election
    // ═════════════════════════════════════════════════════════════════════════

    public void startElection() {
        int electionTerm, lastLogIdx, lastLogTrm;

        synchronized (this) {
            if (!isActive || role == NodeRole.LEADER) return;
            currentTerm++;
            electionTerm = currentTerm;
            role         = NodeRole.CANDIDATE;
            votedFor     = id;
            lastLogIdx   = lastLogIndexUnsafe();
            lastLogTrm   = lastLogTermUnsafe();
            logger.info("[" + id + "] ── Starting election for term " + electionTerm + " ──");
        }
        // Persist term+votedFor BEFORE sending vote requests
        persistMeta(currentTerm, id);

        electionTimer.reset();
        List<NodeMetaData> peers = getPeers();
        int majority        = (peers.size() + 1) / 2 + 1;
        int peerVotesNeeded = majority - 1;

        if (peerVotesNeeded == 0) { promoteToLeader(electionTerm); return; }

        CountDownLatch voteLatch = new CountDownLatch(peerVotesNeeded);
        for (NodeMetaData peer : peers) {
            replicator.submitVoteRequest(id, electionTerm, lastLogIdx, lastLogTrm, peer, granted -> {
                if (granted) voteLatch.countDown();
            });
        }

        boolean won;
        try {
            won = voteLatch.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            won = false;
        }

        if (won) {
            promoteToLeader(electionTerm);
        } else {
            synchronized (this) {
                if (role == NodeRole.CANDIDATE && currentTerm == electionTerm) {
                    role = NodeRole.FOLLOWER;
                    logger.info("[" + id + "] Election LOST for term " + electionTerm);
                }
            }
        }
    }

    private void promoteToLeader(int electionTerm) {
        synchronized (this) {
            if (role != NodeRole.CANDIDATE || currentTerm != electionTerm) return;
            role            = NodeRole.LEADER;
            currentLeaderId = id;
            logger.info("[" + id + "] ★★★ BECAME LEADER for term " + currentTerm + " ★★★");
        }
        electionTimer.stop();
        heartbeatSender.start();
    }

    public void stepDown(int newTerm, String fromLeaderId) {
        boolean wasLeader;
        synchronized (this) {
            if (newTerm < currentTerm) return;
            wasLeader   = (role == NodeRole.LEADER);
            currentTerm = newTerm;
            votedFor    = null;
            role        = NodeRole.FOLLOWER;
            if (fromLeaderId != null) currentLeaderId = fromLeaderId;
            logger.info("[" + id + "] Stepped down to FOLLOWER at term " + currentTerm);
        }
        persistMeta(currentTerm, null);
        if (wasLeader && heartbeatSender != null) heartbeatSender.stop();
        if (electionTimer != null) electionTimer.reset();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Raft §5.3 — AppendEntries (follower side)
    // ═════════════════════════════════════════════════════════════════════════

    public synchronized boolean onAppendEntries(
            int leaderTerm, String leaderId,
            int prevLogIndex, int prevLogTerm,
            List<LogEntry> entries, int leaderCommit) {

        if (leaderTerm < currentTerm) return false;

        if (leaderTerm > currentTerm) {
            currentTerm = leaderTerm;
            votedFor    = null;
            persistMeta(currentTerm, null);
        }

        if (role != NodeRole.FOLLOWER) {
            if (role == NodeRole.LEADER && heartbeatSender != null) heartbeatSender.stop();
            role = NodeRole.FOLLOWER;
        }
        currentLeaderId = leaderId;

        // Log consistency check
        if (prevLogIndex >= 0) {
            if (prevLogIndex >= raftLog.size()) return false;
            if (raftLog.get(prevLogIndex).term != prevLogTerm) {
                raftLog.subList(prevLogIndex, raftLog.size()).clear();
                return false;
            }
        }

        // Append new entries, writing to WAL first
        for (LogEntry entry : entries) {
            if (entry.index < raftLog.size()) {
                if (raftLog.get(entry.index).term != entry.term) {
                    raftLog.subList(entry.index, raftLog.size()).clear();
                    walAppend(entry);
                    raftLog.add(entry);
                }
            } else {
                walAppend(entry);
                raftLog.add(entry);
            }
        }

        if (leaderCommit > commitIndex) {
            int lastIdx = raftLog.isEmpty() ? -1 : raftLog.get(raftLog.size() - 1).index;
            commitIndex = Math.min(leaderCommit, lastIdx);
        }

        return true;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Raft §5.2 — RequestVote (voter side)
    // ═════════════════════════════════════════════════════════════════════════

    public synchronized boolean onRequestVote(
            int candidateTerm, String candidateId,
            int lastLogIndex, int lastLogTerm) {

        if (candidateTerm < currentTerm) return false;

        if (candidateTerm > currentTerm) {
            currentTerm = candidateTerm;
            votedFor    = null;
            persistMeta(currentTerm, null);
            if (role == NodeRole.LEADER && heartbeatSender != null) heartbeatSender.stop();
            role = NodeRole.FOLLOWER;
        }

        boolean logOk = (lastLogTerm > lastLogTermUnsafe())
                     || (lastLogTerm == lastLogTermUnsafe() && lastLogIndex >= lastLogIndexUnsafe());

        if ((votedFor == null || votedFor.equals(candidateId)) && logOk) {
            votedFor = candidateId;
            persistMeta(currentTerm, votedFor); // persist BEFORE responding
            if (electionTimer != null) electionTimer.reset();
            logger.info("[" + id + "] Granted vote to " + candidateId + " for term " + candidateTerm);
            return true;
        }

        logger.fine("[" + id + "] Denied vote to " + candidateId
            + " (votedFor=" + votedFor + ", logOk=" + logOk + ")");
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Client API — Propose
    // ═════════════════════════════════════════════════════════════════════════

    public String propose(String command) throws Exception {
        int entryIndex;
        synchronized (this) {
            if (!isLeader()) throw new NotLeaderException(currentLeaderId);
            entryIndex = raftLog.size();
            LogEntry entry = new LogEntry(entryIndex, currentTerm, command);
            walAppend(entry);   // WAL first, then memory
            raftLog.add(entry);
        }

        boolean quorumAchieved = replicator.replicateAndWaitForQuorum(this, entryIndex);
        if (!quorumAchieved) {
            throw new Exception("Quorum not achieved for log index " + entryIndex);
        }

        synchronized (this) {
            if (entryIndex > commitIndex) commitIndex = entryIndex;
        }

        // Wait for applier to process this entry (max 5s)
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (lastApplied >= entryIndex) break;
            Thread.sleep(5);
        }
        if (lastApplied < entryIndex) {
            throw new TimeoutException("Timed out waiting for entry " + entryIndex + " to be applied");
        }
        return "OK";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Applier Loop
    // ═════════════════════════════════════════════════════════════════════════

    private void startApplierLoop() {
        applierScheduler.scheduleWithFixedDelay(() -> {
            try {
                int ci = commitIndex;
                while (lastApplied < ci) {
                    int next = lastApplied + 1;
                    LogEntry entry = getLogEntry(next);
                    if (entry == null) break;
                    stateMachine.apply(entry.getLog());
                    lastApplied = next;

                    // Periodic snapshot to bound WAL size
                    if (lastApplied > 0 && lastApplied % SNAPSHOT_INTERVAL == 0) {
                        final int snapIdx = lastApplied;
                        new Thread(() -> takeSnapshot(snapIdx), "snapshot-" + id).start();
                    }
                }
            } catch (Exception e) {
                logger.warning("[" + id + "] Applier error: " + e.getMessage());
            }
        }, 10, 10, TimeUnit.MILLISECONDS);
    }

    /**
     * Serialize current state machine to disk and truncate the WAL.
     * Runs on a dedicated thread (not the applier) to avoid blocking writes.
     */
    public void takeSnapshot(int snapIndex) {
        int snapTerm;
        Map<String, String> state;

        synchronized (this) {
            if (snapIndex > lastApplied || snapIndex < 0) return;
            LogEntry entry = getLogEntry(snapIndex);
            snapTerm = (entry != null) ? entry.term : currentTerm;
            state = stateMachine.snapshot();
        }

        try {
            snapshotter.save(new SnapshotData(snapIndex, snapTerm, state));
            raftWal.truncateBefore(snapIndex);
            logger.info("[" + id + "] Snapshot saved at index=" + snapIndex
                + " (" + state.size() + " KV pairs)");
        } catch (IOException e) {
            logger.warning("[" + id + "] Snapshot failed: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  gRPC Server
    // ═════════════════════════════════════════════════════════════════════════

    public void startServer() {
        try {
            grpcServer = ServerBuilder.forPort(port)
                .addService(new RaftServiceImpl(this))
                .addService(new AtlasServiceImpl(this))
                .build()
                .start();
            logger.info("[" + id + "] gRPC server listening on port " + port);
        } catch (IOException e) {
            throw new RuntimeException("[" + id + "] Failed to start gRPC server on port " + port, e);
        }
    }

    public void shutdown() {
        isActive = false;
        if (electionTimer   != null) electionTimer.shutdown();
        if (heartbeatSender != null) heartbeatSender.shutdown();
        if (grpcServer      != null) grpcServer.shutdownNow();
        applierScheduler.shutdownNow();
        stateMachine.shutdown();
        try { raftWal.close(); } catch (IOException e) { logger.fine("WAL close: " + e.getMessage()); }
        logger.info("[" + id + "] Node shut down");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** Fire-and-forget metadata persist — logs on failure but doesn't throw. */
    private void persistMeta(int term, String vf) {
        try {
            metaStore.save(term, vf);
        } catch (IOException e) {
            logger.warning("[" + id + "] Metadata persist FAILED (term=" + term + "): " + e.getMessage());
        }
    }

    /** Append one LogEntry to the WAL — called inside synchronized(this). */
    private void walAppend(LogEntry entry) {
        try {
            raftWal.append(new WalEntry(entry.index, entry.term, entry.getLog()));
        } catch (IOException e) {
            logger.warning("[" + id + "] WAL append FAILED for log[" + entry.index + "]: " + e.getMessage());
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getId()                 { return id; }
    public String getHost()               { return host; }
    public int    getPort()               { return port; }
    public boolean isLeader()             { return role == NodeRole.LEADER; }
    public boolean isActive()             { return isActive; }
    public NodeRole getRole()             { return role; }
    public int    getCurrentTerm()        { return currentTerm; }
    public int    getCommitIndex()        { return commitIndex; }
    public String getCurrentLeaderId()    { return currentLeaderId; }
    public StateMachine getStateMachine() { return stateMachine; }

    public void resetElectionTimer() {
        if (electionTimer != null) electionTimer.reset();
    }

    public List<NodeMetaData> getPeers() {
        return clusterMembers.values().stream()
            .filter(m -> !m.getId().equals(id))
            .toList();
    }

    public synchronized LogEntry getLogEntry(int index) {
        if (index < 0 || index >= raftLog.size()) return null;
        return raftLog.get(index);
    }

    public synchronized List<LogEntry> getLog() { return new ArrayList<>(raftLog); }

    public synchronized int getLastLogIndex() { return lastLogIndexUnsafe(); }
    public synchronized int getLastLogTerm()  { return lastLogTermUnsafe(); }

    private int lastLogIndexUnsafe() {
        return raftLog.isEmpty() ? -1 : raftLog.get(raftLog.size() - 1).index;
    }

    private int lastLogTermUnsafe() {
        return raftLog.isEmpty() ? 0 : raftLog.get(raftLog.size() - 1).term;
    }

    public void setIsActive(boolean val) { this.isActive = val; }

    @Override
    public String toString() {
        return id + "[" + role + " t=" + currentTerm + " ci=" + commitIndex + "]";
    }
}
