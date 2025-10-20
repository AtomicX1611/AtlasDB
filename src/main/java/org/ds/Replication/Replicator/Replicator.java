package org.ds.Replication.Replicator;


import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.ds.Replication.Cluster.Cluster;
import org.ds.Replication.Node.Node;
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

    //Perform network replication.
    public void replicateToFollower(String leaderId, String host, int port, String command){
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host,port)
                .usePlaintext()
                .build();
        RaftServiceGrpc.RaftServiceBlockingStub stub = RaftServiceGrpc.newBlockingStub(channel);

        Raft.AppendRequest request = Raft.AppendRequest.newBuilder()
                .setLeaderId(leaderId)
                .setCommand(command)
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

