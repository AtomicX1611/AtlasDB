
import java.util.List;

import Cluster.Cluster;
import Node.Node;

public class server {
    public static void main(String[] args) {
        Cluster cluster = new Cluster(3); 

        try {
             cluster.replicateSynchronous("SET x = 1");  
             cluster.printLogs();  
             List<Node> followers = cluster.getFollowers();
             Node node = followers.get(0);
             System.out.println("Setting inactive for node : "+node.getId());
             node.setIsActive(false);
             cluster.replicateSynchronous("SET X = 2");
             System.out.println("replicated");
             cluster.printLogs();
        } catch (Exception e) {
            System.out.println("Error Occurred : "+e.getMessage());
        }
    } 
    // public void FailFollower(Cluster cluster,int node){
    //     Node follower = cluster.getFollowers().get(node);
    //     //have an isAlive field in Node class for this test.                        
    // }

    // first mvn compile
    //mvn pacakge - Atlas db 
    // then run java -cp target/classes server
    
}
