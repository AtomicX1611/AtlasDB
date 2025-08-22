import Cluster.Cluster;
import Node.Node;

public class server {
    public static void main(String[] args) {
        Cluster cluster = new Cluster(3); 

        try {
             cluster.replicate("SET x=1");
             cluster.replicate("SET y=2");
             
             cluster.printLogs();
        } catch (Exception e) {
            System.out.println("Error Occurred : "+e.getMessage());
        }
    }
    
    public void FailFollower(Cluster cluster,int node){
        Node follower = cluster.getFollowers().get(node);
        //have an isAlive field in Node class for this test.                        
    }
}
