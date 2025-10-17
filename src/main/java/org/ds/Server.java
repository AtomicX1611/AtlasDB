package org.ds;

import org.ds.Replication.Cluster.Cluster;
import org.ds.Replication.Node.Node;

import java.util.List;

public class Server {

        public static void main(String[] var0) {
            Cluster var1 = new Cluster(3);

            try {
                System.out.println("WTF");
                var1.replicateSynchronous("SET x = 1");
                var1.printLogs();
                List var2 = var1.getFollowers();
                Node var3 = (Node)var2.get(0);
                System.out.println("Setting inactive for node : " + var3.getId());
                var3.setIsActive(false);
                var1.replicateSynchronous("SET X = 2");
                var1.printLogs();
                var3.setIsActive(true);
                var1.replicateSynchronous("SET X = 50");
                var1.printLogs();
            } catch (Exception var4) {
                System.out.println("Error Occurred : " + var4.getMessage());
            }
        }
}
