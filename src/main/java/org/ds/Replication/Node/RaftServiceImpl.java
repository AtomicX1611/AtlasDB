package org.ds.Replication.Node;

import io.grpc.stub.StreamObserver;
import org.ds.proto.Raft;
import org.ds.proto.RaftServiceGrpc;

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    @Override
    public void appendEntries(Raft.AppendRequest request, StreamObserver<Raft.AppendResponse> responseObserver) {
        System.out.println("Received append Entry req from Leader "+request.getLeaderId());
        System.out.println("Command : "+request.getCommand());

        Raft.AppendResponse response = Raft.AppendResponse.newBuilder()
                .setSuccess(true)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
