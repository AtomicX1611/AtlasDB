package utils;
public class LogEntry {
    
     final int index;
    private final String log;

    public LogEntry(int index,String log){
       this.index = index;
       this.log = log;
    }
    
     @Override
    public String toString() {
        return "LogEntry{" + "Index=" + index + ", Log='" + log + '\'' + '}';
    }

}
