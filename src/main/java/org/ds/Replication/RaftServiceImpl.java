package org.ds.Replication;

import io.grpc.stub.StreamObserver;
import org.ds.Replication.Node.Node;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.*;

import java.util.List;
import java.util.logging.Logger;

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {
    private static final Logger logger = Logger.getLogger(RaftServiceImpl.class.getName());
    private final Node node;

    public RaftServiceImpl(Node node) {
        this.node = node;
    }

    @Override
    public void appendEntries(AppendRequest request, StreamObserver<AppendResponse> observer) {
        List<LogEntry> entries = request.getEntriesList().stream()
            .map(e -> new LogEntry(e.getIndex(), e.getTerm(), e.getCommand()))
            .toList();

        boolean success = node.onAppendEntries(
            request.getTerm(),
            request.getLeaderId(),
            request.getPrevLogIndex(),
            request.getPrevLogTerm(),
            entries,
            request.getLeaderCommit()
        );
        if (request.getTerm() >= node.getCurrentTerm()) {
            node.resetElectionTimer();
        }

        observer.onNext(AppendResponse.newBuilder()
            .setTerm(node.getCurrentTerm())
            .setSuccess(success)
            .setMatchIndex(node.getLastLogIndex())
            .build());
        observer.onCompleted();
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> observer) {
        boolean granted = node.onRequestVote(
            request.getTerm(),
            request.getCandidateId(),
            request.getLastLogIndex(),
            request.getLastLogTerm()
        );

        logger.info("[" + node.getId() + "] RequestVote from " + request.getCandidateId()
            + " term=" + request.getTerm() + " → " + (granted ? "GRANTED" : "DENIED"));

        observer.onNext(VoteResponse.newBuilder()
            .setTerm(node.getCurrentTerm())
            .setVoteGranted(granted)
            .build());
        observer.onCompleted();
    }

    /**
     * InstallSnapshot RPC handler (Phase 3).
     * Invoked by the leader when a follower is too far behind to catch up
     * via normal AppendEntries (its required log entries were already compacted).
     */
    @Override
    public void installSnapshot(SnapshotRequest request, StreamObserver<SnapshotResponse> observer) {
        logger.info("[" + node.getId() + "] InstallSnapshot from " + request.getLeaderId()
            + " term=" + request.getTerm()
            + " lastIndex=" + request.getLastIncludedIndex()
            + " lastTerm="  + request.getLastIncludedTerm());

        boolean success = node.onInstallSnapshot(
            request.getTerm(),
            request.getLeaderId(),
            request.getLastIncludedIndex(),
            request.getLastIncludedTerm(),
            request.getData().toByteArray()
        );

        observer.onNext(SnapshotResponse.newBuilder()
            .setTerm(node.getCurrentTerm())
            .setSuccess(success)
            .build());
        observer.onCompleted();
    }
}
