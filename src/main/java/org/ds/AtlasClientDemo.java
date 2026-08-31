package org.ds;

import org.ds.client.AtlasClient;
import org.ds.client.AtlasClient.AtlasException;

import java.util.*;
import java.util.logging.*;

/**
 * AtlasClientDemo — Phase 4 Smart Client Demonstration.
 *
 * Exercises:
 *  1. Single put / get / delete through the smart client
 *  2. Automatic leader discovery on startup
 *  3. Batch put (N pairs in one RPC, pipelined through Raft)
 *  4. Auto-redirect verification (prints which node answered)
 *
 * Prerequisites:
 *   Start the cluster first:
 *     .\scripts\start-cluster.ps1
 *   Or in-process demo:
 *     mvn "-Dexec.mainClass=org.ds.Server" exec:java
 *
 * Then run this demo:
 *   java -cp target\atlasdb-jar-with-dependencies.jar org.ds.AtlasClientDemo
 */
public class AtlasClientDemo {

    public static void main(String[] args) throws Exception {
        setupLogging();

        List<String> seeds = List.of(
            "localhost:50050",
            "localhost:50051",
            "localhost:50052"
        );

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║      AtlasDB — Phase 4 Smart Client Demo                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝\n");

        try (AtlasClient client = new AtlasClient(seeds)) {

            // ── 1. Single writes ──────────────────────────────────────────────
            section("1. Single Writes");
            put(client, "name",       "AtlasDB");
            put(client, "version",    "3.0");
            put(client, "consensus",  "Raft");
            put(client, "storage",    "LSM-tree");
            put(client, "transport",  "gRPC");

            // ── 2. Reads ──────────────────────────────────────────────────────
            section("2. Reads");
            get(client, "name");
            get(client, "version");
            get(client, "missing_key");  // expects null → (nil)

            // ── 3. Batch put ──────────────────────────────────────────────────
            section("3. Batch Put (10 pairs → 1 RPC)");
            Map<String, String> batch = new LinkedHashMap<>();
            for (int i = 1; i <= 10; i++) {
                batch.put("batch_key_" + i, "batch_value_" + i);
            }
            long t0 = System.currentTimeMillis();
            int committed = client.batchPut(batch);
            long elapsed  = System.currentTimeMillis() - t0;
            System.out.printf("  BatchPut: %d/%d committed in %d ms%n",
                committed, batch.size(), elapsed);

            // Verify a few batch entries
            get(client, "batch_key_1");
            get(client, "batch_key_10");

            // ── 4. Delete ─────────────────────────────────────────────────────
            section("4. Delete");
            del(client, "version");
            get(client, "version");  // should be nil now

            // ── 5. Verify persistence (important for Phase 2) ─────────────────
            section("5. Final State");
            get(client, "name");
            get(client, "consensus");
            get(client, "storage");
            get(client, "transport");

            System.out.println("\n✅  Demo complete — all operations routed through Raft consensus.\n");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void put(AtlasClient client, String key, String value) {
        try {
            String result = client.put(key, value);
            System.out.printf("  PUT  %-20s = %-20s  →  %s%n", key, value, result);
        } catch (AtlasException e) {
            System.out.printf("  PUT  %-20s  ✗  %s%n", key, e.getMessage());
        }
    }

    private static void get(AtlasClient client, String key) {
        try {
            String value = client.get(key);
            System.out.printf("  GET  %-20s  →  %s%n", key,
                value != null ? value : "(nil)");
        } catch (AtlasException e) {
            System.out.printf("  GET  %-20s  ✗  %s%n", key, e.getMessage());
        }
    }

    private static void del(AtlasClient client, String key) {
        try {
            boolean deleted = client.delete(key);
            System.out.printf("  DEL  %-20s  →  %s%n", key,
                deleted ? "deleted" : "not found");
        } catch (AtlasException e) {
            System.out.printf("  DEL  %-20s  ✗  %s%n", key, e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println("\n  ── " + title + " " + "─".repeat(Math.max(0, 50 - title.length())));
    }

    private static void setupLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.WARNING); // suppress INFO noise during demo
        for (Handler h : root.getHandlers()) h.setLevel(Level.WARNING);
    }
}
