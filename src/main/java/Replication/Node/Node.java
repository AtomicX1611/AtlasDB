package Replication.Node;
import java.util.ArrayList;
import java.util.List;

import Replication.utils.LogEntry;

public class Node {
    private boolean isActive;
    private boolean isLeader;
    private final List<LogEntry> logs;
    private final String id;
    
    public Node(String id,boolean isLeader){
            this.isLeader = isLeader;
            this.id = id;
            this.logs = new ArrayList<>();
            isActive = true;
    }
    
    public String getId(){
        return id;
    }
    
    public boolean isLeader(){return isLeader;}

    public void setLeader(boolean leader){this.isLeader = leader;}

    public void append(LogEntry entry) {
        logs.add(entry);
        // System.out.println(id + " appended: " + entry);
    }

    public List<LogEntry> getLog() { return logs; }

    public boolean getIsActive(){
        return isActive;
    }

    public void setIsActive(boolean val){
        isActive = val;
    }

}
