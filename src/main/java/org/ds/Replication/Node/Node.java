package org.ds.Replication.Node;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.ds.Replication.Cluster.NodeMetaData;
import org.ds.Replication.RaftServiceImpl;
import org.ds.Replication.utils.LogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Node {
    private boolean isLeader;
    private final List<LogEntry> logs;
    private final String id;
    private final String host;
    private final int port;
    private boolean isActive;
    private final Map<String, NodeMetaData> clusterMembers;
    private Server grpcServer;
    private int currentTerm;
    private int commitIndex;

    public Node(String id, boolean isLeader, String host, int port, Map<String, NodeMetaData> clusterMembers){
        this.isLeader = isLeader;
        this.id = id;
        this.clusterMembers = clusterMembers;
        this.logs = new ArrayList<>();
        isActive = true;
        this.host = host;
        this.port = port;
        this.currentTerm = 1;
        this.commitIndex = -1;
    }

    public String getId(){
        return id;
    }

    public boolean isLeader(){return isLeader;}

    public void setLeader(boolean leader){this.isLeader = leader;}

    public void append(LogEntry entry) {
        logs.add(entry);
    }

    public List<LogEntry> getLog() { return logs; }

    public int getCurrentTerm(){ return currentTerm; }

    public void setCurrentTerm(int term){ this.currentTerm = term; }

    public int getCommitIndex(){ return commitIndex; }

    public void setCommitIndex(int commitIndex){ this.commitIndex = commitIndex; }

    public int getLastLogIndex(){ return logs.isEmpty() ? -1 : logs.get(logs.size() - 1).index; }

    public boolean getIsActive(){
        return isActive;
    }

    public void setIsActive(boolean val){
        isActive = val;
    }

    public void startServer(){
        try{
            grpcServer = ServerBuilder.forPort(port)
                    .addService(new RaftServiceImpl(this))
                    .build()
                    .start();
            System.out.println("Started the grpc server on port "+port);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void shutDown(){
        if(grpcServer != null){
            grpcServer.shutdown();
            System.out.println("server shutting down for id : "+id);
        }
    }

}
