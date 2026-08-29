package org.ds.storage;

import org.ds.storage.lsm.LSMEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Logger;

public class StateMachine {
    private static final Logger logger = Logger.getLogger(StateMachine.class.getName());

    private final LSMEngine lsm;

    public StateMachine(Path dataDir) throws IOException {
        this.lsm = new LSMEngine(dataDir);
    }

    public String apply(String command) {
        if (command == null || command.isBlank()) return "ERR empty command";
        String[] parts = command.trim().split("\\s+", 3);
        return switch (parts[0].toUpperCase()) {
            case "SET" -> {
                if (parts.length < 3) yield "ERR SET requires key and value";
                lsm.put(parts[1], parts[2]);
                yield "OK";
            }
            case "GET" -> {
                if (parts.length < 2) yield "ERR GET requires key";
                String v = lsm.get(parts[1]);
                yield v != null ? v : "(nil)";
            }
            case "DEL" -> {
                if (parts.length < 2) yield "ERR DEL requires key";
                boolean existed = lsm.get(parts[1]) != null;
                lsm.delete(parts[1]);
                yield existed ? "1" : "0";
            }
            default -> "ERR unknown command: " + parts[0];
        };
    }

    public String get(String key) {
        String v = lsm.get(key);
        return v != null ? v : "(nil)";
    }

    public boolean exists(String key) {
        return lsm.get(key) != null;
    }

    /** Returns a deep copy of the current live state (no tombstones). For Raft snapshotting. */
    public Map<String, String> snapshot() {
        return lsm.snapshot();
    }

    /** Restore full state from a Raft snapshot. */
    public void restore(Map<String, String> data) {
        lsm.restore(data);
    }

    public void shutdown() {
        lsm.shutdown();
    }

    public LSMEngine getLsm() { return lsm; }
}
