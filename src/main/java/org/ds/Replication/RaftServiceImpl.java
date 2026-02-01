package org.ds.Replication;

import io.grpc.stub.StreamObserver;
import org.ds.Replication.Node.Node;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.*;
import org.ds.proto.RaftServiceGrpc;

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private final Node node;

    public RaftServiceImpl(Node node) {
        this.node = node;
    }

    @Override
    public void appendEntries(AppendRequest request, StreamObserver<AppendResponse> responseObserver) {
        boolean success = true;
        int matchIndex = -1;

        try {
            if (request.getTerm() < node.getCurrentTerm()) {
                success = false;
            } else {
                if (request.getTerm() > node.getCurrentTerm()) {
                    node.setCurrentTerm(request.getTerm());
                }

                for (org.ds.proto.LogEntry entry : request.getEntriesList()) {
                    node.append(new LogEntry(entry.getIndex(), entry.getTerm(), entry.getCommand()));
                    matchIndex = entry.getIndex();
                }

                node.setCommitIndex(Math.max(node.getCommitIndex(), request.getLeaderCommit()));
            }
        } catch (Exception e) {
            success = false;
        }

        AppendResponse response = AppendResponse.newBuilder()
                .setTerm(node.getCurrentTerm())
                .setSuccess(success)
                .setMatchIndex(matchIndex)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        boolean granted = false;

        if (request.getTerm() < node.getCurrentTerm()) {
            granted = false;
        } else {
            if (request.getTerm() > node.getCurrentTerm()) {
                node.setCurrentTerm(request.getTerm());
            }
            granted = true;
        }

        VoteResponse response = VoteResponse.newBuilder()
                .setTerm(node.getCurrentTerm())
                .setVoteGranted(granted)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }


}
