package org.ds;

import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.Node.Node;
import org.ds.Replication.Replicator.Replicator;

import java.util.*;
import java.util.logging.*;

/**
 * Single-node entry point for multi-process deployment (Phase 3).
 *
 * Each JVM process represents ONE AtlasDB node. Launch N processes
 * to form a cluster — they discover each other via the --peers argument.
 *
 * ── Usage ──────────────────────────────────────────────────────────────────
 *
 *   java -jar atlasdb.jar <nodeId> <host> <port> <peers>
 *
 *   <peers>  comma-separated list of ALL nodes (including self):
 *            id:host:port,id:host:port,...
 *
 * ── Environment variables (Docker-compatible alternative) ──────────────────
 *
 *   ATLAS_NODE_ID   node identifier (e.g. node-0)
 *   ATLAS_HOST      bind address   (e.g. localhost or 0.0.0.0)
 *   ATLAS_PORT      gRPC port      (e.g. 50050)
 *   ATLAS_PEERS     comma-separated peer list (same format as CLI arg)
 *
 * ── Priority ───────────────────────────────────────────────────────────────
 *   CLI args > environment variables > built-in defaults
 *
 * ── Example (3-node local cluster) ─────────────────────────────────────────
 *   Terminal 1: java -jar atlasdb.jar node-0 localhost 50050 node-0:localhost:50050,node-1:localhost:50051,node-2:localhost:50052
 *   Terminal 2: java -jar atlasdb.jar node-1 localhost 50051 node-0:localhost:50050,node-1:localhost:50051,node-2:localhost:50052
 *   Terminal 3: java -jar atlasdb.jar node-2 localhost 50052 node-0:localhost:50050,node-1:localhost:50051,node-2:localhost:50052
 *
 * ── Docker-compatible example (one container per node) ─────────────────────
 *   ENV ATLAS_NODE_ID=node-0
 *   ENV ATLAS_HOST=0.0.0.0
 *   ENV ATLAS_PORT=50050
 *   ENV ATLAS_PEERS=node-0:node-0-svc:50050,node-1:node-1-svc:50050,...
 *   CMD ["java", "-jar", "atlasdb.jar"]
 */
public class NodeLauncher {

    private static final Logger logger = Logger.getLogger(NodeLauncher.class.getName());

    private static final String DEFAULT_PEERS_SINGLE_NODE = "node-0:localhost:50050";

    public static void main(String[] args) throws InterruptedException {
        // Parse nodeId first so we can set up per-node log file immediately
        String nodeId = param(args, 0, "ATLAS_NODE_ID", "node-0");
        setupLogging(nodeId);

        String host   = param(args, 1, "ATLAS_HOST",    "localhost");
        int    port   = Integer.parseInt(param(args, 2, "ATLAS_PORT", "50050"));
        String peers  = param(args, 3, "ATLAS_PEERS",   nodeId + ":" + host + ":" + port);

        Map<String, NodeMetaData> members = parsePeers(peers);

        if (!members.containsKey(nodeId)) {
            logger.severe("nodeId '" + nodeId + "' not found in peers list: " + peers);
            System.exit(1);
        }

        logger.info("┌─────────────────────────────────────────────────────┐");
        logger.info("│  AtlasDB NodeLauncher  —  Phase 3 Multi-Process     │");
        logger.info("├─────────────────────────────────────────────────────┤");
        logger.info("│  nodeId  = " + nodeId);
        logger.info("│  address = " + host + ":" + port);
        logger.info("│  cluster = " + members.keySet());
        logger.info("└─────────────────────────────────────────────────────┘");

        Replicator replicator = new Replicator();
        Node node = new Node(nodeId, host, port, members);

        // Graceful shutdown on Ctrl+C / SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[" + nodeId + "] Shutdown signal received...");
            node.shutdown();
            replicator.shutdown();
        }, "shutdown-hook"));

        node.startServer();
        node.init(replicator);

        logger.info("[" + nodeId + "] Node running. Waiting for election...");
        logger.info("[" + nodeId + "] Press Ctrl+C to stop.");

        // Block main thread forever — node runs on daemon threads
        Thread.currentThread().join();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Parse "id:host:port,id:host:port,..." into NodeMetaData map. */
    private static Map<String, NodeMetaData> parsePeers(String peers) {
        Map<String, NodeMetaData> map = new LinkedHashMap<>();
        for (String token : peers.split(",")) {
            String[] parts = token.trim().split(":");
            if (parts.length != 3) {
                logger.warning("Ignoring malformed peer token: '" + token
                    + "' (expected id:host:port)");
                continue;
            }
            String id = parts[0].trim();
            String h  = parts[1].trim();
            int    p;
            try {
                p = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException e) {
                logger.warning("Ignoring peer with bad port: " + token);
                continue;
            }
            map.put(id, new NodeMetaData(id, h, p));
        }
        return map;
    }

    /** CLI arg > env var > default. */
    private static String param(String[] args, int idx, String env, String def) {
        if (args != null && args.length > idx && !args[idx].isBlank()) return args[idx];
        String v = System.getenv(env);
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static void setupLogging(String nodeId) {
        // Write logs to logs/<nodeId>.log so the process is not dependent on
        // the parent shell's stdout/stderr pipe (critical for Windows multi-process)
        try {
            java.io.File logDir = new java.io.File("logs");
            logDir.mkdirs();

            java.util.logging.FileHandler fh = new java.util.logging.FileHandler(
                "logs/" + nodeId + ".log", /* append */ true);
            fh.setFormatter(new java.util.logging.Formatter() {
                private final java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("HH:mm:ss.SSS");
                @Override public String format(java.util.logging.LogRecord r) {
                    return String.format("[%s] [%-7s] %s%n",
                        sdf.format(new java.util.Date(r.getMillis())),
                        r.getLevel(), r.getMessage());
                }
            });

            java.util.logging.Logger root = Logger.getLogger("");
            // Remove default console handlers to keep the terminal clean
            for (java.util.logging.Handler h : root.getHandlers()) {
                root.removeHandler(h);
            }
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (java.io.IOException e) {
            System.err.println("WARNING: Could not set up file logging: " + e.getMessage());
        }
    }
}
