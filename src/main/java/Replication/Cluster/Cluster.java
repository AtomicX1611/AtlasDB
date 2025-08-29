package Replication.Cluster;
import java.util.ArrayList;
import java.util.List;

import Replication.Node.Node;
import Replication.Replicator.Replicator;
import Replication.utils.LogEntry;

public class Cluster{
    private final List<Node> nodes;
    private final Node leader;
    private final Replicator replicator;

    public Cluster(int n){
          replicator = new Replicator();
          nodes = new ArrayList<>();
          leader = new Node("leader", true);

          for(int i = 1;i<n;i++){
                 nodes.add(new Node("Follower-" + i, false));
          }
    }

    public Node getLeader() { return leader; }

    public List<Node> getFollowers() { 
        return nodes; 
    }
    
    public void replicateSynchronous(String command){
        int index = (leader.getLog().size() == 0) ? 0 : leader.getLog().size();
        LogEntry entry = new LogEntry(index, command);
        leader.append(entry);
        replicator.replicateSync(leader, this, command);
    }

    public void replicateAsynchronous(String command){
       int index = (leader.getLog().size() == 0) ? 0 : leader.getLog().size();
       LogEntry entry = new LogEntry(index, command);
       leader.append(entry);
       replicator.replicateAsync(leader, this, entry);
    }

    public void printLogs() {
        System.out.println("Printing Logs : ");

        System.out.println(leader.getId()+" log : "+leader.getLog());
        for (Node node : nodes){
            System.out.println(node.getId() + " log: " + node.getLog());
        }
    }
}