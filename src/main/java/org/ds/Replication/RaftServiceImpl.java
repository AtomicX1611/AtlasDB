package org.ds.Replication;

import io.grpc.stub.StreamObserver;
import org.ds.Replication.Node.Node;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.Raft;
import org.ds.proto.RaftServiceGrpc;

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private final Node node;

    public RaftServiceImpl(Node node) {
        this.node = node;
    }

    @Override
    public void appendEntries(Raft.AppendRequest request, StreamObserver<Raft.AppendResponse> responseObserver) {
       try{
           node.append(new LogEntry(request.getEntryIndex(), request.getCommand()));
           Raft.AppendResponse response = Raft.AppendResponse.newBuilder()
                   .setSuccess(true)
                   .build();
           responseObserver.onNext(response);
           responseObserver.onCompleted();
       } catch (Exception e) {
           Raft.AppendResponse response = Raft.AppendResponse.newBuilder()
                   .setSuccess(false)
                   .build();
           responseObserver.onNext(response);
           responseObserver.onCompleted();
       }
    }
}
