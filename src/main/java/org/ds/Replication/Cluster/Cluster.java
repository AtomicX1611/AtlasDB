package org.ds.Replication.Cluster;


import org.ds.Replication.Node.Node;
import org.ds.Replication.Replicator.Replicator;
import org.ds.Replication.utils.LogEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Cluster{
    private final List<Node> nodes;
    private Node leader;
    private final Replicator replicator;
    private final Map<String,NodeMetaData> clusterMembers;

    public Cluster(int n){
        replicator = new Replicator();
        nodes = new ArrayList<>();
        clusterMembers = new HashMap<>();

        for(int i = 1;i<n;i++){
            String id = (i == 0) ? "leader" : "Follower-" + i;
            boolean isLeader = (i == 0);
            String host = "localhost";
            int port = 50050 + i;
            clusterMembers.put(id, new NodeMetaData(id, host, port));
        }

        for (Map.Entry<String, NodeMetaData> entry : clusterMembers.entrySet()) {
            NodeMetaData meta = entry.getValue();
            Node node = new Node(meta.getId(), meta.getId().equals("leader"), meta.getHost(), meta.getPort(), clusterMembers);
            nodes.add(node);
            if (meta.getId().equals("leader")) leader = node;
        }

        for(Node node : nodes){
            node.startServer();
        }
        leader.startServer();
    }

    public List<Node> getFollowers() {
        return nodes.stream().filter(n -> !n.isLeader()).toList();
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

    public void replicate(String command){
        int index = leader.getLog().size();
        LogEntry entry = new LogEntry(index, command);

        leader.append(entry);

        for(Node node : getFollowers()){
            if(node.getIsActive()){
                NodeMetaData data = clusterMembers.get(node.getId());
                replicator.replicateToFollower(leader.getId(), data.getHost(), data.getPort(), command);
            }
        }
    }

    public void printLogs() {
        System.out.println("Printing Logs : ");

        System.out.println(leader.getId()+" log : "+leader.getLog());
        for (Node node : nodes){
            System.out.println(node.getId() + " log: " + node.getLog());
        }
    }
}
