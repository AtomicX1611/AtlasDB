package org.ds.Replication.Cluster;

import org.ds.Replication.Node.Node;
import org.ds.Replication.Replicator.Replicator;

import java.util.*;
import java.util.logging.Logger;

public class Cluster {
    private static final Logger logger = Logger.getLogger(Cluster.class.getName());

    private final List<Node> nodes;
    private final Replicator replicator;

    public Cluster(int n) {
        this.replicator = new Replicator();
        Map<String, NodeMetaData> members = new LinkedHashMap<>();
        this.nodes = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String id   = "node-" + i;
            int    port = 50050 + i;
            members.put(id, new NodeMetaData(id, "localhost", port));
        }

        for (NodeMetaData meta : members.values()) {
            Node node = new Node(meta.getId(), meta.getHost(), meta.getPort(), members);
            node.startServer();
            node.init(replicator);
            nodes.add(node);
        }

        logger.info("Cluster of " + n + " nodes started on ports 50050–" + (50050 + n - 1));
    }

    public void waitForLeader(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (getLeader() != null) return;
            Thread.sleep(50);
        }
        throw new RuntimeException("No leader elected within " + timeoutMs + "ms — "
            + "check for port conflicts or election timer misconfiguration");
    }

    public Node getLeader() {
        return nodes.stream().filter(Node::isLeader).findFirst().orElse(null);
    }

    public Node getNode(String id) {
        return nodes.stream().filter(n -> n.getId().equals(id)).findFirst().orElse(null);
    }

    public List<Node> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public int countLeaders() {
        return (int) nodes.stream().filter(Node::isLeader).count();
    }


    public void printStatus() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                     Cluster Status                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        for (Node node : nodes) {
            String marker = node.isLeader() ? "★ " : "  ";
            System.out.printf("║ %s%-10s [%-9s] term=%-4d commitIdx=%-4d log=%s%n",
                marker, node.getId(), node.getRole(),
                node.getCurrentTerm(), node.getCommitIndex(),
                node.getLog());
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
    }

    public void shutdown() {
        logger.info("Shutting down cluster...");
        nodes.forEach(Node::shutdown);
        replicator.shutdown();
    }
}
