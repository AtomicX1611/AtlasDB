package org.ds.Replication.Replicator;


import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.*;
import org.ds.proto.RaftServiceGrpc;

public class Replicator {

    public void replicateToFollower(
            String leaderId,
            int leaderTerm,
            int prevLogIndex,
            int prevLogTerm,
            int leaderCommit,
            String host,
            int port,
            LogEntry entry){
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host,port)
                .usePlaintext()
                .build();
        RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel);

        org.ds.proto.LogEntry logEntry = org.ds.proto.LogEntry.newBuilder()
                .setIndex(entry.index)
                .setTerm(entry.term)
            .setCommand(entry.getLog())
                .build();

        AppendRequest request = AppendRequest.newBuilder()
                .setLeaderId(leaderId)
                .setTerm(leaderTerm)
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .addEntries(logEntry)
                .setLeaderCommit(leaderCommit)
                .build();

        try {
            //Kinda the beauty of gRPC 
            // makes it seem like this was just like a method call.
            AppendResponse response = stub.appendEntries(request);
            System.out.println("Response from follower (" + host + ":" + port + ") => " + response.getSuccess());
        } catch (Exception e) {
            System.err.println("Failed to replicate to " + host + ":" + port + " => " + e.getMessage());
        } finally {
            channel.shutdown();
        }
    }

}

