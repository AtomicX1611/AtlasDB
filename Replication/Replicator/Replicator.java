package Replicator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Node.Node;
import Cluster.Cluster;
import utils.LogEntry;

public class Replicator {
    private ExecutorService executorService = Executors.newFixedThreadPool(4);

    public void replicateAsync(Node leader,Cluster cluster,LogEntry entry){
      leader.append(entry);
      List<Node> followers = cluster.getFollowers();
      for(Node follower : followers){
           executorService.submit(
            () -> {
             try {
                 Thread.sleep((long) (Math.random()*200));
                 follower.append(entry);
                 System.out.println("Follower "+follower.getId()+" has caught up with entry ");
             } catch (Exception e) {
                     
             }
            }
           );
      }

    }

    public void replicateSync(Node leader,Cluster cluster){
     
    }
}
