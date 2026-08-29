package org.ds.Replication.Node;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.HeartbeatSender;
import org.ds.Replication.RaftElectionTimer;
import org.ds.Replication.RaftServiceImpl;
import org.ds.Replication.Replicator.Replicator;
import org.ds.Replication.utils.LogEntry;
import org.ds.client.AtlasServiceImpl;
import org.ds.client.NotLeaderException;
import org.ds.storage.StateMachine;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Node {
    private static final Logger logger = Logger.getLogger(Node.class.getName());

    private final String id;
    private final String host;
    private final int port;
    private final Map<String, NodeMetaData> clusterMembers;
    private volatile int currentTerm = 0;
    private volatile String votedFor   = null;
    private volatile NodeRole role            = NodeRole.FOLLOWER;
    private volatile String   currentLeaderId = null;
    private final List<LogEntry> raftLog      = new ArrayList<>();
    private volatile int commitIndex = -1;
    private volatile int lastApplied = -1;


    private volatile boolean isActive = true;
    private Server grpcServer;
    private Replicator replicator;
    private RaftElectionTimer electionTimer;
    private HeartbeatSender heartbeatSender;

    private final StateMachine stateMachine = new StateMachine();

    private ScheduledExecutorService applierScheduler; 

    public Node(String id, String host, int port, Map<String, NodeMetaData> clusterMembers) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.clusterMembers = clusterMembers;
        this.applierScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "applier-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Finalize node initialization. Must be called AFTER startServer() so that
     * gRPC servers on all peers are ready to receive vote/append RPCs when the
     * election timer fires.
     *
     * @param replicator Shared Replicator (injected to break circular dep with timers).
     */
    public void init(Replicator replicator) {
        this.replicator    = replicator;
        this.electionTimer = new RaftElectionTimer(this);
        this.heartbeatSender = new HeartbeatSender(this, replicator);
        startApplierLoop();
        electionTimer.start(); // start listening for leader heartbeats
        logger.info("[" + id + "] Initialized as FOLLOWER, election timer armed");
    }

   
    public void startElection() {
        int electionTerm;
        int lastLogIdx;
        int lastLogTrm;

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

        electionTimer.reset();
        List<NodeMetaData> peers = getPeers();
        int majority      = (peers.size() + 1) / 2 + 1;
        int peerVotesNeeded = majority - 1;          

        if (peerVotesNeeded == 0) {
            promoteToLeader(electionTerm);
            return;
        }
        CountDownLatch voteLatch = new CountDownLatch(peerVotesNeeded);

        for (NodeMetaData peer : peers) {
            replicator.submitVoteRequest(id, electionTerm, lastLogIdx, lastLogTrm, peer, granted -> {
                if (granted) {
                    logger.fine("[" + id + "] Vote received from " + peer.getId() + " for term " + electionTerm);
                    voteLatch.countDown();
                }
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
                    logger.info("[" + id + "] Election LOST for term " + electionTerm + " (no majority)");
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
            if (newTerm < currentTerm) return; // ignore stale
            wasLeader   = (role == NodeRole.LEADER);
            currentTerm = newTerm;
            votedFor    = null;
            role        = NodeRole.FOLLOWER;
            if (fromLeaderId != null) currentLeaderId = fromLeaderId;
            logger.info("[" + id + "] Stepped down to FOLLOWER at term " + currentTerm);
        }
        if (wasLeader && heartbeatSender != null) heartbeatSender.stop();
        if (electionTimer != null) electionTimer.reset(); // restart the follower timeout
    }


    /**
     * Process an AppendEntries RPC (heartbeat or real entries) from the leader.
     *
     * @return true on success; false if rejected (stale term or log inconsistency)
     */
    public synchronized boolean onAppendEntries(
            int leaderTerm, String leaderId,
            int prevLogIndex, int prevLogTerm,
            List<LogEntry> entries, int leaderCommit) {

        // Rule §5.1: Reject stale leaders
        if (leaderTerm < currentTerm) {
            logger.fine("[" + id + "] Rejected AppendEntries — stale term " + leaderTerm);
            return false;
        }

        // Valid leader contact — update term if higher
        if (leaderTerm > currentTerm) {
            currentTerm = leaderTerm;
            votedFor    = null;
        }

        // If we were a candidate or leader, revert to follower now
        if (role != NodeRole.FOLLOWER) {
            if (role == NodeRole.LEADER && heartbeatSender != null) heartbeatSender.stop();
            role = NodeRole.FOLLOWER;
        }
        currentLeaderId = leaderId;

        if (prevLogIndex >= 0) {
            if (prevLogIndex >= raftLog.size()) {
                return false;
            }
            if (raftLog.get(prevLogIndex).term != prevLogTerm) {
                // Conflicting entry at prevLogIndex — delete from here onwards
                raftLog.subList(prevLogIndex, raftLog.size()).clear();
                return false;
            }
        }

        // Append new entries (skip if already present with matching term — idempotent)
        for (LogEntry entry : entries) {
            if (entry.index < raftLog.size()) {
                if (raftLog.get(entry.index).term != entry.term) {
                    // Conflict: truncate and rewrite
                    raftLog.subList(entry.index, raftLog.size()).clear();
                    raftLog.add(entry);
                }
                // else: same term at same index → skip (already have it)
            } else {
                raftLog.add(entry);
            }
        }

        if (leaderCommit > commitIndex) {
            int lastIdx  = raftLog.isEmpty() ? -1 : raftLog.get(raftLog.size() - 1).index;
            commitIndex  = Math.min(leaderCommit, lastIdx);
        }

        return true;
    }


    public synchronized boolean onRequestVote(
            int candidateTerm, String candidateId,
            int lastLogIndex, int lastLogTerm) {

        if (candidateTerm < currentTerm) return false;

        if (candidateTerm > currentTerm) {
            currentTerm = candidateTerm;
            votedFor    = null;
            if (role == NodeRole.LEADER && heartbeatSender != null) heartbeatSender.stop();
            role = NodeRole.FOLLOWER;
        }

        boolean logOk = (lastLogTerm > lastLogTermUnsafe())
                     || (lastLogTerm == lastLogTermUnsafe() && lastLogIndex >= lastLogIndexUnsafe());

        if ((votedFor == null || votedFor.equals(candidateId)) && logOk) {
            votedFor = candidateId;
            if (electionTimer != null) electionTimer.reset();
            logger.info("[" + id + "] Granted vote to " + candidateId + " for term " + candidateTerm);
            return true;
        }

        logger.fine("[" + id + "] Denied vote to " + candidateId
            + " (votedFor=" + votedFor + ", logOk=" + logOk + ")");
        return false;
    }


    /**
     * Propose a command through Raft consensus. Blocks until:
     *   1. The entry is replicated to a majority of nodes (committed), AND
     *   2. The local applier thread applies it to the state machine.
     *
     * Only callable on the leader. Followers receive NOT_LEADER via AtlasServiceImpl.
     *
     * @param command e.g. "SET x 10", "DEL y"
     * @return "OK" for SET/DEL (result value tracking deferred to Phase 5)
     */
    public String propose(String command) throws Exception {
        int entryIndex;

        synchronized (this) {
            if (!isLeader()) throw new NotLeaderException(currentLeaderId);
            entryIndex = raftLog.size();
            raftLog.add(new LogEntry(entryIndex, currentTerm, command));
        }

        logger.fine("[" + id + "] Proposing log[" + entryIndex + "]: " + command);

    
        boolean quorumAchieved = replicator.replicateAndWaitForQuorum(this, entryIndex);
        if (!quorumAchieved) {
            throw new Exception("Quorum not achieved for log index " + entryIndex
                + " — leader may have been deposed");
        }

  
        synchronized (this) {
            if (entryIndex > commitIndex) {
                commitIndex = entryIndex;
            }
        }

       
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (lastApplied >= entryIndex) break;
            Thread.sleep(5);
        }

        if (lastApplied < entryIndex) {
            throw new TimeoutException("Timed out waiting for entry " + entryIndex + " to be applied");
        }

        logger.fine("[" + id + "] Committed and applied log[" + entryIndex + "]: " + command);
        return "OK";
    }




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
                    logger.fine("[" + id + "] Applied log[" + next + "]: " + entry.getLog());
                }
            } catch (Exception e) {
                logger.warning("[" + id + "] Applier error: " + e.getMessage());
            }
        }, 10, 10, TimeUnit.MILLISECONDS);
    }


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
        logger.info("[" + id + "] Node shut down");
    }


    public String getId()                  { return id; }
    public String getHost()                { return host; }
    public int    getPort()                { return port; }
    public boolean isLeader()              { return role == NodeRole.LEADER; }
    public boolean isActive()              { return isActive; }
    public NodeRole getRole()              { return role; }
    public int    getCurrentTerm()         { return currentTerm; }
    public int    getCommitIndex()         { return commitIndex; }
    public String getCurrentLeaderId()     { return currentLeaderId; }
    public StateMachine getStateMachine()  { return stateMachine; }

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

    public synchronized List<LogEntry> getLog() {
        return new ArrayList<>(raftLog);
    }

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
