package org.ds;

import org.ds.Replication.Cluster.Cluster;
import org.ds.Replication.Node.Node;

import java.util.List;

public class Server {

        public static void main(String[] var0) {
            Cluster cluster = new Cluster(3);

            cluster.replicate("SET x = 10");
            cluster.replicate("SET y = 20");

            // Print logs on leader and followers
            cluster.printLogs();

        }
}
