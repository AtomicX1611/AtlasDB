package Replication;
import java.util.List;

import Replication.Cluster.Cluster;
import Replication.Node.Node;

public class server {
    public static void main(String[] args) {
        Cluster cluster = new Cluster(3); 

        try{
             cluster.replicateAsynchronous("Set X = 10");
             cluster.printLogs();
        } catch(Exception e) {
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
