package Cluster;
import java.util.ArrayList;
import java.util.List;

import Node.Node;
import utils.LogEntry;

public class Cluster{
    private final List<Node> nodes;
    private final Node leader;

    public Cluster(int n){
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
    
     public void replicate(String command){
        int index = leader.getLog().size();
        LogEntry prevLog;
        
        if(index > 0) prevLog = leader.getLog().get(index-1);
        else prevLog = null;

        LogEntry entry = new LogEntry(index + 1, command);

        leader.append(entry); 

        try {
            for (Node follower : getFollowers()) {
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
            
        }
        } catch (Exception e) {
            System.out.println("Exception occurred : "+e.getMessage());
        }
    }

    public void printLogs() {
        for (Node node : nodes){
            if(!node.isLeader())
            System.out.println(node.getId() + " log: " + node.getLog());
        }
    }
}