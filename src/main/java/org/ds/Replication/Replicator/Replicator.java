package org.ds.Replication.Replicator;


import org.ds.Replication.Cluster.Cluster;
import org.ds.Replication.Node.Node;
import org.ds.Replication.utils.LogEntry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Replicator {

    private ExecutorService executorService = Executors.newFixedThreadPool(4);

    public void Async(Node leader, Cluster cluster, LogEntry entry){
        List<Node> followers = cluster.getFollowers();
        for(Node follower : followers){
            executorService.submit(
                    () -> {
                        try{
                            follower.append(entry);
                            System.out.println("Follower "+follower.getId()+" has caught up with entry ");
                        } catch (Exception e){
                            System.out.println("Exception Occurred for Follower "+follower.getId());
                        }
                    }
            );
        }
    }

    public void replicateAsync(Node leader, Cluster cluster, LogEntry entry) {
        List<Node> followers = cluster.getFollowers();
        ExecutorService executorService = Executors.newFixedThreadPool(4);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(followers.size());

        for (Node follower : followers){
            followerAppend(executorService, follower, entry)
                    .thenAccept(success -> {
                        if (success) successCount.incrementAndGet();
                        latch.countDown();
                    })
                    .exceptionally(ex -> {
                        System.err.println("Replication failed: " + ex.getMessage());
                        latch.countDown();
                        return null;
                    });
        }

        CompletableFuture.runAsync(() -> {
            try {
                latch.await();
                System.out.println("Async replication complete. Success: " + successCount.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                executorService.shutdown();
            }
        });
    }

    public CompletableFuture<Boolean> followerAppend(ExecutorService service, Node follower, LogEntry entry) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (follower.getId().equals("Follower-1")) {
                    System.out.println(follower.getId() + " needs to wait for 2s");
                    Thread.sleep(2000);
                }
                follower.append(entry);
                return true;
            } catch (Exception e) {
                System.out.println("Exception occurred: " + e.getMessage());
                return false;
            }
        }, service);
    }

    public void replicateSync(Node leader,Cluster cluster,String command){
        int index = leader.getLog().size() - 1;
        LogEntry prevLog;
        if(index > 0) prevLog = leader.getLog().get(index-1);
        else prevLog = null;

        LogEntry entry = new LogEntry(index, command);
        try {
            for (Node follower : cluster.getFollowers()){
                if(follower.getIsActive()){
                    List<LogEntry> followerLogs = follower.getLog();
                    LogEntry lastFollowerLog = null;
                    if(followerLogs.size() > 0) lastFollowerLog = followerLogs.get(followerLogs.size() -1);

                    if(prevLog == null || lastFollowerLog == null) {
                        follower.append(entry);
                        continue;
                    }
                    if(prevLog.index == lastFollowerLog.index){
                        follower.append(entry);
                    }else{
                        List<LogEntry> leaderLogs = leader.getLog();
                        int i = 0;
                        while(i < leaderLogs.size() && i < followerLogs.size() && leaderLogs.get(i).index == followerLogs.get(i).index) i++;
                        while (followerLogs.size() > i) followerLogs.remove(followerLogs.size() - 1);
                        for (int j = i; j < leaderLogs.size(); j++) follower.append(leaderLogs.get(j));
                    }
                }else {
                    System.out.println(follower.getId()+" Is not active");
                }
            }
        } catch (Exception e){
            System.out.println("Exception occurred : "+e.getMessage());
        }
    }
}

