package Replication.Replicator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import Replication.Node.Node;
import Replication.Cluster.Cluster;
import Replication.utils.LogEntry;

public class Replicator {

    private ExecutorService executorService = Executors.newFixedThreadPool(4);

    public void replicateAsync(Node leader,Cluster cluster,LogEntry entry){
      List<Node> followers = cluster.getFollowers();
      for(Node follower : followers){
           executorService.submit(
            () -> {
             try {
                 follower.append(entry);
                 System.out.println("Follower "+follower.getId()+" has caught up with entry ");
                } catch (Exception e) {
                System.out.println("Exception Occurred for Follower "+follower.getId());     
             }  
            }
           );
      }
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
