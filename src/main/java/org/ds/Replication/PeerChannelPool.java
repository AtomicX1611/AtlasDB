package org.ds.Replication;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe persistent gRPC channel pool.
 *
 * Phase 1 fix: The original Replicator opened and closed a brand-new ManagedChannel
 * per RPC call — a massive overhead (TCP handshake + TLS negotiation every time).
 * This pool caches one live channel per "host:port", eliminating that cost and
 * enabling HTTP/2 connection reuse across concurrent RPCs to the same peer.
 *
 * Phase 5 hook: swap forAddress() builder for a load-balanced channel if needed.
 */
public class PeerChannelPool {

    private final ConcurrentHashMap<String, ManagedChannel> pool = new ConcurrentHashMap<>();

    /**
     * Returns an existing channel for this address, or creates and caches a new one.
     * Thread-safe via ConcurrentHashMap.computeIfAbsent.
     */
    public ManagedChannel getOrCreate(String host, int port) {
        return pool.computeIfAbsent(host + ":" + port, key ->
            ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)   // keep channel warm even with no traffic
                .build());
    }

    /** Gracefully shuts down all cached channels (called on cluster shutdown). */
    public void shutdown() {
        pool.values().forEach(ManagedChannel::shutdownNow);
        pool.clear();
    }
}
