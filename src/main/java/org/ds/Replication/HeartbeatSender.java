package org.ds.Replication;

import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.Node.Node;
import org.ds.Replication.Replicator.Replicator;

import java.util.concurrent.*;
import java.util.logging.Logger;

public class HeartbeatSender {
    private static final Logger logger = Logger.getLogger(HeartbeatSender.class.getName());
    static final int HEARTBEAT_INTERVAL_MS = 100;

    private final Node node;
    private final Replicator replicator;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> heartbeatTask;

    public HeartbeatSender(Node node, Replicator replicator) {
        this.node = node;
        this.replicator = replicator;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-" + node.getId());
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) return;
        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::sendHeartbeats, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("[" + node.getId() + "] Heartbeat sender STARTED");
    }

    public synchronized void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
            logger.info("[" + node.getId() + "] Heartbeat sender STOPPED");
        }
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    private void sendHeartbeats() {
        if (!node.isLeader() || !node.isActive()) {
            stop();
            return;
        }
        for (NodeMetaData peer : node.getPeers()) {
            try {
                replicator.sendHeartbeat(node, peer);
            } catch (Exception e) {
                logger.fine("[" + node.getId() + "] Heartbeat to " + peer.getId() + " error: " + e.getMessage());
            }
        }
    }
}
