package org.ds.Replication.Cluster;

import java.util.Map;

public class NodeMetaData {
    private final String id ;
    private final String host;
    private final int port;

    public NodeMetaData(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }

    public String getId() { return id; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    @Override
    public String toString() {
        return id + " (" + host + ":" + port + ")";
    }

}
