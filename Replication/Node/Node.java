package Node;
import java.util.ArrayList;
import java.util.List;

import utils.LogEntry;

public class Node {
    private boolean isLeader;
    private final List<LogEntry> logs;
    private final String id;
    
    public Node(String id,boolean isLeader){
            this.isLeader = isLeader;
            this.id = id;
            this.logs = new ArrayList<>();
    }
    
    public String getId(){
        return id;
    }
    public boolean isLeader(){return isLeader;}

    public void setLeader(boolean leader){this.isLeader = leader;}

    public void append(LogEntry entry) {
        logs.add(entry);
        System.out.println(id + " appended: " + entry);
    }

    public List<LogEntry> getLog() { return logs; }


}
