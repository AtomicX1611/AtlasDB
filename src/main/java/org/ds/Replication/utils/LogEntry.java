package org.ds.Replication.utils;

public class LogEntry {

    public final int index;
    public final int term;
    private final String log;

    public LogEntry(int index, int term, String log){
        this.index = index;
        this.term = term;
        this.log = log;
    }

    public String getLog(){
        return log;
    }

    @Override
    public String toString() {
        return "LogEntry{ " + "Index=" + index + ", Term=" + term + ", Log='" + log + '\'' + '}';
    }

}

