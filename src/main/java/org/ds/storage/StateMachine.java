package org.ds.storage;

import java.util.concurrent.ConcurrentSkipListMap;

public class StateMachine {

    private final ConcurrentSkipListMap<String, String> store = new ConcurrentSkipListMap<>();


    public String apply(String command) {
        if (command == null || command.isBlank()) return "ERR empty command";
        String[] parts = command.trim().split("\\s+", 3);

        return switch (parts[0].toUpperCase()) {
            case "SET" -> {
                if (parts.length < 3) yield "ERR SET requires key and value";
                store.put(parts[1], parts[2]);
                yield "OK";
            }
            case "GET" -> {
                if (parts.length < 2) yield "ERR GET requires key";
                yield store.getOrDefault(parts[1], "(nil)");
            }
            case "DEL" -> {
                if (parts.length < 2) yield "ERR DEL requires key";
                yield store.remove(parts[1]) != null ? "1" : "0";
            }
            default -> "ERR unknown command: " + parts[0];
        };
    }

    public String get(String key) {
        return store.getOrDefault(key, "(nil)");
    }


    public boolean exists(String key) {
        return store.containsKey(key);
    }

    public ConcurrentSkipListMap<String, String> snapshot() {
        return new ConcurrentSkipListMap<>(store);
    }


    public void restore(ConcurrentSkipListMap<String, String> data) {
        store.clear();
        store.putAll(data);
    }

    @Override
    public String toString() {
        return store.toString();
    }
}
