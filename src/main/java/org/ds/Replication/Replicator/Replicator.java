package org.ds.Replication.Replicator;


import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.ds.Replication.utils.LogEntry;
import org.ds.proto.Raft;
import org.ds.proto.RaftServiceGrpc;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

        Raft.LogEntry logEntry = Raft.LogEntry.newBuilder()
                .setIndex(entry.index)
                .setTerm(entry.term)
            .setCommand(entry.getLog())
                .build();

        Raft.AppendRequest request = Raft.AppendRequest.newBuilder()
                .setLeaderId(leaderId)
                .setTerm(leaderTerm)
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .addEntries(logEntry)
                .setLeaderCommit(leaderCommit)
                .build();

        try {
            Raft.AppendResponse response = stub.appendEntries(request);
            System.out.println("Response from follower (" + host + ":" + port + ") => " + response.getSuccess());
        } catch (Exception e) {
            System.err.println("Failed to replicate to " + host + ":" + port + " => " + e.getMessage());
        } finally {
            channel.shutdown();
        }
    }

}

