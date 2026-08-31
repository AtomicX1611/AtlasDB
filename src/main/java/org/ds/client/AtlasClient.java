package org.ds.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.ds.proto.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * AtlasClient — Phase 4 Smart Client for AtlasDB.
 *
 * Features:
 *  1. Leader caching   — maintains the address of the current leader so
 *                        most requests skip the redirect handshake entirely.
 *  2. Auto-redirect    — on NOT_LEADER, reads the leader_hint from the
 *                        response and retries against the hinted address.
 *  3. Auto-discovery   — on startup (or after total redirect failure) calls
 *                        GetLeader on each seed node to find the leader.
 *  4. Exponential back-off — retries on transient gRPC errors.
 *  5. Batch writes     — batchPut() pipelines N key-value pairs in a single
 *                        RPC; the server fans them out through Raft in parallel.
 *
 * Usage:
 * <pre>
 *   var addrs = List.of("localhost:50050", "localhost:50051", "localhost:50052");
 *   try (AtlasClient client = new AtlasClient(addrs)) {
 *       client.put("name", "AtlasDB");
 *       System.out.println(client.get("name")); // AtlasDB
 *       client.batchPut(Map.of("k1","v1","k2","v2"));
 *       client.delete("k1");
 *   }
 * </pre>
 *
 * Thread-safety: all public methods are thread-safe.
 */
public class AtlasClient implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(AtlasClient.class.getName());

    private static final int MAX_REDIRECTS  = 5;
    private static final int MAX_RETRIES    = 3;
    private static final int DEADLINE_MS    = 3_000;

    // Ordered list of seed addresses used for discovery (host:port)
    private final List<String> seeds;

    // Per-address gRPC channels — created lazily, never closed until client.close()
    private final ConcurrentHashMap<String, ManagedChannel>               channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtlasServiceGrpc.AtlasServiceBlockingStub> stubs   = new ConcurrentHashMap<>();

    // Cached leader address ("host:port") — updated on every redirect / discovery
    private volatile String leaderAddress;

    // Peer ID → host:port mapping populated after leader discovery
    private final ConcurrentHashMap<String, String> peerAddressCache = new ConcurrentHashMap<>();

    /**
     * @param seedAddresses One or more "host:port" strings for cluster nodes.
     *                      Does not need to be exhaustive — discovery will find the leader.
     */
    public AtlasClient(List<String> seedAddresses) {
        if (seedAddresses == null || seedAddresses.isEmpty())
            throw new IllegalArgumentException("AtlasClient requires at least one seed address");
        this.seeds = new ArrayList<>(seedAddresses);
        this.leaderAddress = seeds.get(0); // optimistic first guess
        discoverLeader();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Write a key-value pair through Raft consensus.
     * Automatically redirects to the leader if needed.
     *
     * @return "OK" on success
     * @throws AtlasException on unrecoverable failure
     */
    public String put(String key, String value) throws AtlasException {
        for (int redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
            try {
                PutResponse resp = stub().put(
                    PutRequest.newBuilder().setKey(key).setValue(value).build());

                if (resp.getSuccess()) return "OK";

                if ("NOT_LEADER".equals(resp.getError())) {
                    redirectToLeader(resp.getLeaderHint());
                    continue;
                }
                throw new AtlasException("PUT failed: " + resp.getError());

            } catch (StatusRuntimeException e) {
                handleRpcFailure(e, redirect);
            }
        }
        throw new AtlasException("PUT exceeded redirect limit for key: " + key);
    }

    /**
     * Read a value by key from the leader's state machine.
     *
     * @return the value, or null if the key does not exist
     * @throws AtlasException on unrecoverable failure
     */
    public String get(String key) throws AtlasException {
        for (int redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
            try {
                GetResponse resp = stub().get(
                    GetRequest.newBuilder().setKey(key).build());

                if (resp.getError().isEmpty()) {
                    return resp.getFound() ? resp.getValue() : null;
                }
                if ("NOT_LEADER".equals(resp.getError())) {
                    redirectToLeader(resp.getLeaderHint());
                    continue;
                }
                throw new AtlasException("GET failed: " + resp.getError());

            } catch (StatusRuntimeException e) {
                handleRpcFailure(e, redirect);
            }
        }
        throw new AtlasException("GET exceeded redirect limit for key: " + key);
    }

    /**
     * Delete a key. Returns true if the key existed.
     *
     * @throws AtlasException on unrecoverable failure
     */
    public boolean delete(String key) throws AtlasException {
        for (int redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
            try {
                DeleteResponse resp = stub().delete(
                    DeleteRequest.newBuilder().setKey(key).build());

                if (resp.getError().isEmpty()) return resp.getDeleted();

                if ("NOT_LEADER".equals(resp.getError())) {
                    redirectToLeader(resp.getLeaderHint());
                    continue;
                }
                throw new AtlasException("DEL failed: " + resp.getError());

            } catch (StatusRuntimeException e) {
                handleRpcFailure(e, redirect);
            }
        }
        throw new AtlasException("DEL exceeded redirect limit for key: " + key);
    }

    /**
     * Write multiple key-value pairs in a single RPC.
     * The server pipelines all pairs through Raft concurrently.
     *
     * Client-perceived latency ≈ max(single-write latency) rather than sum.
     *
     * @param kvs Map of key → value pairs to write
     * @return number of successfully committed pairs
     * @throws AtlasException on unrecoverable failure
     */
    public int batchPut(Map<String, String> kvs) throws AtlasException {
        if (kvs == null || kvs.isEmpty()) return 0;

        BatchPutRequest.Builder reqBuilder = BatchPutRequest.newBuilder();
        for (Map.Entry<String, String> e : kvs.entrySet()) {
            reqBuilder.addPairs(KVPair.newBuilder()
                .setKey(e.getKey())
                .setValue(e.getValue())
                .build());
        }
        BatchPutRequest req = reqBuilder.build();

        for (int redirect = 0; redirect < MAX_REDIRECTS; redirect++) {
            try {
                BatchPutResponse resp = stub().batchPut(req);

                if (resp.getError().isEmpty()) {
                    logger.info("BatchPut: success=" + resp.getSuccessCount()
                        + " fail=" + resp.getFailCount());
                    return resp.getSuccessCount();
                }
                if ("NOT_LEADER".equals(resp.getError())) {
                    redirectToLeader(resp.getLeaderHint());
                    continue;
                }
                throw new AtlasException("BatchPut failed: " + resp.getError());

            } catch (StatusRuntimeException e) {
                handleRpcFailure(e, redirect);
            }
        }
        throw new AtlasException("BatchPut exceeded redirect limit");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Leader discovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Probes each seed node with GetLeader until one responds with a valid leader.
     * Updates leaderAddress and populates peerAddressCache.
     */
    private void discoverLeader() {
        for (String seed : seeds) {
            try {
                AtlasServiceGrpc.AtlasServiceBlockingStub s = getStub(seed);
                GetLeaderResponse resp = s.getLeader(GetLeaderRequest.newBuilder().build());

                if (!resp.getLeaderId().isEmpty() && resp.getLeaderPort() > 0) {
                    String addr = resp.getLeaderHost() + ":" + resp.getLeaderPort();
                    peerAddressCache.put(resp.getLeaderId(), addr);
                    leaderAddress = addr;
                    logger.info("[AtlasClient] Leader discovered: "
                        + resp.getLeaderId() + " @ " + addr);
                    return;
                }
            } catch (Exception e) {
                logger.fine("[AtlasClient] GetLeader from " + seed + " failed: " + e.getMessage());
            }
        }
        // No leader found yet — fall back to first seed; cluster may still be electing
        logger.warning("[AtlasClient] No leader discovered during startup; using " + leaderAddress);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Redirect logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Update the leader address cache based on a hint from a NOT_LEADER response.
     *
     * The hint is a node ID (e.g. "node-1"). We look it up in peerAddressCache.
     * If not found, we call discoverLeader() to re-probe the cluster.
     */
    private void redirectToLeader(String leaderHint) {
        if (leaderHint == null || leaderHint.isEmpty()) {
            logger.fine("[AtlasClient] Redirect hint empty — re-discovering");
            discoverLeader();
            return;
        }

        // Check if we know this node's address already
        String addr = peerAddressCache.get(leaderHint);
        if (addr != null) {
            logger.fine("[AtlasClient] Redirecting to cached address: " + leaderHint + " @ " + addr);
            leaderAddress = addr;
            return;
        }

        // The hint is a node ID but not in our cache — try seed nodes for resolution
        for (String seed : seeds) {
            try {
                GetLeaderResponse resp = getStub(seed)
                    .getLeader(GetLeaderRequest.newBuilder().build());
                if (resp.getLeaderId().equals(leaderHint) && resp.getLeaderPort() > 0) {
                    addr = resp.getLeaderHost() + ":" + resp.getLeaderPort();
                    peerAddressCache.put(leaderHint, addr);
                    leaderAddress = addr;
                    logger.info("[AtlasClient] Resolved leader hint " + leaderHint + " → " + addr);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // Last resort: fall through to full discovery
        discoverLeader();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  RPC failure handling
    // ─────────────────────────────────────────────────────────────────────────

    private void handleRpcFailure(StatusRuntimeException e, int attempt) throws AtlasException {
        if (attempt >= MAX_RETRIES - 1) {
            // Assume leader went down; re-discover
            logger.warning("[AtlasClient] RPC failed (" + e.getStatus().getCode()
                + ") — re-discovering leader");
            discoverLeader();
        } else {
            try {
                Thread.sleep(50L * (1L << attempt)); // 50ms, 100ms, 200ms
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AtlasException("Interrupted during retry back-off");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Channel / stub management
    // ─────────────────────────────────────────────────────────────────────────

    private AtlasServiceGrpc.AtlasServiceBlockingStub stub() {
        return getStub(leaderAddress);
    }

    private AtlasServiceGrpc.AtlasServiceBlockingStub getStub(String address) {
        return stubs.computeIfAbsent(address, addr -> {
            String[] parts = addr.split(":", 2);
            ManagedChannel ch = ManagedChannelBuilder
                .forAddress(parts[0], Integer.parseInt(parts[1]))
                .usePlaintext()
                .build();
            channels.put(addr, ch);
            return AtlasServiceGrpc.newBlockingStub(ch)
                .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS);
        });
    }

    @Override
    public void close() {
        channels.values().forEach(ch -> {
            ch.shutdown();
            try { ch.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        channels.clear();
        stubs.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Checked exception
    // ─────────────────────────────────────────────────────────────────────────

    public static class AtlasException extends Exception {
        public AtlasException(String msg) { super(msg); }
    }
}
