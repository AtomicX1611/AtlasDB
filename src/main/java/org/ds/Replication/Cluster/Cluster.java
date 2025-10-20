package org.ds.Replication.Cluster;


import org.ds.Replication.Node.Node;
import org.ds.Replication.Replicator.Replicator;
import org.ds.Replication.utils.LogEntry;

import java.io.IOException;
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

        for(int i = 0;i<n;i++){
            String id = (i == 0) ? "leader" : "Follower-" + i;
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

        try{
            for(Node node : nodes){
                node.startServer();
            }
        } catch (Exception e) {
            System.out.println("Exception in initilaization : ");
            e.printStackTrace();
        }
    }

    public List<Node> getFollowers() {
        return nodes.stream().filter(n -> !n.isLeader()).toList();
    }

    public void replicate(String command){
        int index = leader.getLog().size();
        LogEntry entry = new LogEntry(index, command);

        leader.append(entry);

        for(Node node : getFollowers()){
            if(node.getIsActive() && !node.isLeader()){
                NodeMetaData data = clusterMembers.get(node.getId());
                replicator.replicateToFollower(leader.getId(), data.getHost(), data.getPort(), command);
            }
        }
    }

    public void printLogs() {
        System.out.println("Printing Logs : ");
        for (Node node : nodes){
            System.out.println(node.getId() + " log: " + node.getLog());
        }
    }
}
