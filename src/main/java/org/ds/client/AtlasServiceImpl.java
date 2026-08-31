package org.ds.client;

import io.grpc.stub.StreamObserver;
import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.Node.Node;
import org.ds.proto.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class AtlasServiceImpl extends AtlasServiceGrpc.AtlasServiceImplBase {
    private static final Logger logger = Logger.getLogger(AtlasServiceImpl.class.getName());

    private final Node node;

    public AtlasServiceImpl(Node node) {
        this.node = node;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Put
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(notLeaderPut());
            observer.onCompleted();
            return;
        }
        try {
            node.propose("SET " + request.getKey() + " " + request.getValue());
            observer.onNext(PutResponse.newBuilder().setSuccess(true).build());
        } catch (NotLeaderException e) {
            observer.onNext(PutResponse.newBuilder()
                .setSuccess(false)
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(e.getCurrentLeaderId()))
                .build());
        } catch (Exception e) {
            logger.warning("[" + node.getId() + "] PUT failed: " + e.getMessage());
            observer.onNext(PutResponse.newBuilder()
                .setSuccess(false)
                .setError(e.getMessage())
                .build());
        }
        observer.onCompleted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Get
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(GetResponse.newBuilder()
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
                .build());
            observer.onCompleted();
            return;
        }
        String value = node.getStateMachine().get(request.getKey());
        observer.onNext(GetResponse.newBuilder()
            .setValue(value)
            .setFound(!value.equals("(nil)"))
            .build());
        observer.onCompleted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Delete
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(notLeaderDel());
            observer.onCompleted();
            return;
        }
        try {
            node.propose("DEL " + request.getKey());
            observer.onNext(DeleteResponse.newBuilder().setDeleted(true).build());
        } catch (NotLeaderException e) {
            observer.onNext(DeleteResponse.newBuilder()
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(e.getCurrentLeaderId()))
                .build());
        } catch (Exception e) {
            logger.warning("[" + node.getId() + "] DEL failed: " + e.getMessage());
            observer.onNext(DeleteResponse.newBuilder()
                .setError(e.getMessage())
                .build());
        }
        observer.onCompleted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BatchPut  (Phase 4)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Accepts multiple KV pairs in one RPC and pipelines them through Raft in
     * parallel. Each pair becomes its own Raft log entry so individual failures
     * are isolatable. The response reports success/fail counts.
     *
     * Client-perceived latency ≈ max(individual propose latency) rather than
     * sum, since all entries are proposed concurrently.
     */
    @Override
    public void batchPut(BatchPutRequest request, StreamObserver<BatchPutResponse> observer) {
        if (!node.isLeader()) {
            observer.onNext(BatchPutResponse.newBuilder()
                .setError("NOT_LEADER")
                .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
                .build());
            observer.onCompleted();
            return;
        }

        List<KVPair> pairs = request.getPairsList();
        if (pairs.isEmpty()) {
            observer.onNext(BatchPutResponse.newBuilder().setSuccessCount(0).build());
            observer.onCompleted();
            return;
        }

        // Pipeline all proposes in parallel using a fixed pool
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(pairs.size(), 16));
        List<Future<Boolean>> futures = new ArrayList<>();

        for (KVPair kv : pairs) {
            futures.add(pool.submit(() -> {
                try {
                    node.propose("SET " + kv.getKey() + " " + kv.getValue());
                    return true;
                } catch (Exception e) {
                    logger.fine("[" + node.getId() + "] BatchPut entry failed: " + e.getMessage());
                    return false;
                }
            }));
        }

        pool.shutdown();

        int successCount = 0;
        int failCount    = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get(10, TimeUnit.SECONDS)) successCount++;
                else failCount++;
            } catch (Exception e) {
                failCount++;
            }
        }

        observer.onNext(BatchPutResponse.newBuilder()
            .setSuccessCount(successCount)
            .setFailCount(failCount)
            .build());
        observer.onCompleted();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GetLeader  (Phase 4 — smart client discovery)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current known leader's address.
     * Any node can answer this — it just reads the local leaderHint.
     * The smart client calls this on startup or after a redirect failure to
     * discover the live leader without knowing the cluster topology upfront.
     */
    @Override
    public void getLeader(GetLeaderRequest request, StreamObserver<GetLeaderResponse> observer) {
        String leaderId = node.getCurrentLeaderId();
        GetLeaderResponse.Builder resp = GetLeaderResponse.newBuilder();

        if (leaderId != null && !leaderId.isEmpty()) {
            NodeMetaData meta = node.getPeerMetaData(leaderId);
            resp.setLeaderId(leaderId);
            if (meta != null) {
                resp.setLeaderHost(meta.getHost());
                resp.setLeaderPort(meta.getPort());
            } else if (leaderId.equals(node.getId())) {
                // This node is the leader
                resp.setLeaderHost(node.getHost());
                resp.setLeaderPort(node.getPort());
            }
        }

        observer.onNext(resp.build());
        observer.onCompleted();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private PutResponse notLeaderPut() {
        return PutResponse.newBuilder()
            .setSuccess(false)
            .setError("NOT_LEADER")
            .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
            .build();
    }

    private DeleteResponse notLeaderDel() {
        return DeleteResponse.newBuilder()
            .setError("NOT_LEADER")
            .setLeaderHint(nullSafe(node.getCurrentLeaderId()))
            .build();
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
