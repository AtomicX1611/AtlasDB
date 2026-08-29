package org.ds.Replication;

import org.ds.Replication.Node.Node;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
 
public class RaftElectionTimer {
    private static final Logger logger = Logger.getLogger(RaftElectionTimer.class.getName());

    static final int MIN_TIMEOUT_MS = 150;
    static final int MAX_TIMEOUT_MS = 300;

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
        logger.info("[" + node.getId() + "] Election timeout fired — starting election");
        Thread electionThread = new Thread(node::startElection,
            "election-" + node.getId() + "-t" + System.currentTimeMillis());
        electionThread.setDaemon(true);
        electionThread.start();
    }
}
