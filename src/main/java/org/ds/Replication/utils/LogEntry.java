package org.ds.Replication.utils;

public class LogEntry {

    public final int index;
    private final String log;

    public LogEntry(int index,String log){
        this.index = index;
        this.log = log;
    }
    public static void main(String[] args) {
        System.out.println("Running LogEntry");
    }

    @Override
    public String toString() {
        return "LogEntry{ " + "Index=" + index + ", Log='" + log + '\'' + '}';
    }

}

