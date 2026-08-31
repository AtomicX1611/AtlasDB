package org.ds.Replication;

import org.ds.Replication.Node.Node;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
 
public class RaftElectionTimer {
    private static final Logger logger = Logger.getLogger(RaftElectionTimer.class.getName());

    // Wider range gives inter-process gRPC vote RPCs time to complete
    // before a new election is triggered (150–600ms — Raft paper recommends
    // at least 10x heartbeat interval; heartbeat = 100ms here)
    static final int MIN_TIMEOUT_MS = 200;
    static final int MAX_TIMEOUT_MS = 600;

    private final Node node;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pending;
    private final Object timerLock = new Object();
    private final AtomicBoolean active = new AtomicBoolean(true);

    public RaftElectionTimer(Node node) {
        this.node = node;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "election-timer-" + node.getId());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        reset();
    }

    public void reset() {
        if (!active.get()) return;
        synchronized (timerLock) {
            if (pending != null) pending.cancel(false);
            int delay = MIN_TIMEOUT_MS + ThreadLocalRandom.current().nextInt(MAX_TIMEOUT_MS - MIN_TIMEOUT_MS);
            pending = scheduler.schedule(this::onTimeout, delay, TimeUnit.MILLISECONDS);
        }
    }

    
    public void stop() {
        synchronized (timerLock) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }
    }

    public void shutdown() {
        active.set(false);
        stop();
        scheduler.shutdownNow();
    }

    private void onTimeout() {
        if (!node.isActive()) return;
        // Don't trigger another election if we're already a candidate/leader
        // (avoids split-vote storm when RPCs are slow across separate JVM processes)
        if (node.getRole() != org.ds.Replication.Node.NodeRole.FOLLOWER) {
            reset(); // reschedule but don't start a new election
            return;
        }
        logger.info("[" + node.getId() + "] Election timeout fired — starting election");
        Thread electionThread = new Thread(node::startElection,
            "election-" + node.getId() + "-t" + System.currentTimeMillis());
        electionThread.setDaemon(true);
        electionThread.start();
    }
}
