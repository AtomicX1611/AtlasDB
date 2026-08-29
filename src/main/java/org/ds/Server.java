package org.ds;

import org.ds.Replication.Cluster.Cluster;
import org.ds.Replication.Node.Node;

import java.util.logging.Logger;

public class Server {
    private static final Logger logger = Logger.getLogger(Server.class.getName());

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tF %1$tT] [%4$-7s] %5$s%6$s%n");

        banner();

        Cluster cluster = new Cluster(3);
        Runtime.getRuntime().addShutdownHook(new Thread(cluster::shutdown, "shutdown-hook"));

        logger.info("Waiting for leader election (timeout 4s)...");
        cluster.waitForLeader(4_000);

        Node leader = cluster.getLeader();
        logger.info("Leader elected → " + leader.getId() + "  (port " + leader.getPort() + ")");

        logger.info("Writing entries through Raft consensus...");
        leader.propose("SET x 10");
        leader.propose("SET y 20");
        leader.propose("SET name AtlasDB");

        Thread.sleep(300); 

        logger.info("Reading from leader state machine:");
        logger.info("  GET x    = " + leader.getStateMachine().get("x"));
        logger.info("  GET y    = " + leader.getStateMachine().get("y"));
        logger.info("  GET name = " + leader.getStateMachine().get("name"));

        cluster.printStatus();

        logger.info("Verifying follower logs converged:");
        for (Node node : cluster.getNodes()) {
            logger.info("  " + node.getId() + " log size=" + node.getLog().size()
                + " commitIdx=" + node.getCommitIndex());
        }

        logger.info("\nAtlasDB cluster is live. gRPC endpoints:");
        for (Node node : cluster.getNodes()) {
            String role = node.isLeader() ? " ← leader" : "";
            logger.info("  " + node.getId() + " → localhost:" + node.getPort() + role);
        }
        logger.info("Press Ctrl+C to stop.\n");

        Thread.currentThread().join();
    }

    private static void banner() {
        System.out.println("""
               AtlasDB — Distributed KV Store     
                Java · gRPC · Raft · Phase 1        
        """);
    }
}
