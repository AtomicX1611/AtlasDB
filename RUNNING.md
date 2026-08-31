# AtlasDB — Running Guide (Phase 1 → 4)

## Project Overview

AtlasDB is a distributed key-value store built from scratch in Java using:
- **gRPC + Protobuf** — inter-node RPCs (Raft consensus + client API)
- **Custom Raft** — leader election, log replication, quorum commits, InstallSnapshot
- **Custom LSM-tree** — MemTable → SSTable flush, Bloom filters, sparse index
- **Write-Ahead Log** — CRC32-framed, fdatasync'd for crash durability
- **Smart Client** — leader caching, NOT_LEADER auto-redirect, batch writes

---

## Quick-Start Options

### Option A — In-Process Demo (simplest, all 3 nodes in one JVM)
```powershell
cd d:\Documents\Atlas_DB
mvn "-Dexec.mainClass=org.ds.Server" exec:java
```
All 3 nodes share a JVM. Good for debugging.

### Option B — Multi-Process Cluster (Phase 3, production-like)
```powershell
# 1. Build the fat JAR (only needed once after source changes)
mvn package -DskipTests
# Output: target\atlasdb-jar-with-dependencies.jar  (~18 MB)

# 2. Start 3 nodes as separate JVM processes
.\scripts\start-cluster.ps1
# Logs: logs\node-0.log  logs\node-1.log  logs\node-2.log

# 3. Tail a node
Get-Content logs\node-0.log -Wait

# 4. Stop
.\scripts\stop-cluster.ps1
```

### Option C — Individual nodes in separate terminals (most visible)
```powershell
# Terminal 1
.\scripts\start-node.ps1 -NodeId node-0 -Port 50050

# Terminal 2
.\scripts\start-node.ps1 -NodeId node-1 -Port 50051

# Terminal 3
.\scripts\start-node.ps1 -NodeId node-2 -Port 50052
```

---

## Process Count & Port Layout

| Node     | Port  | Data Directory    | WAL File              | Meta File             |
|----------|-------|-------------------|-----------------------|-----------------------|
| node-0   | 50050 | `data/node-0/`    | `data/node-0/raft.wal`| `data/node-0/meta.bin`|
| node-1   | 50051 | `data/node-1/`    | `data/node-1/raft.wal`| `data/node-1/meta.bin`|
| node-2   | 50052 | `data/node-2/`    | `data/node-2/raft.wal`| `data/node-2/meta.bin`|

Starting with N nodes creates N JVM processes + N gRPC servers + N WAL files on disk.

---

## NodeLauncher — CLI Reference (Phase 3)

```
java -jar target\atlasdb-jar-with-dependencies.jar <nodeId> <host> <port> <peers>

Arguments:
  nodeId   Unique node identifier, e.g. node-0
  host     Bind address, e.g. localhost or 0.0.0.0 (for Docker)
  port     gRPC listen port, e.g. 50050
  peers    Comma-separated cluster members: id:host:port,id:host:port,...

Environment variable equivalents (Docker-compatible):
  ATLAS_NODE_ID   overrides nodeId
  ATLAS_HOST      overrides host
  ATLAS_PORT      overrides port
  ATLAS_PEERS     overrides peers
```

### Example — 3 terminals, 3 processes:
```powershell
$PEERS = "node-0:localhost:50050,node-1:localhost:50051,node-2:localhost:50052"
java -jar target\atlasdb-jar-with-dependencies.jar node-0 localhost 50050 $PEERS
java -jar target\atlasdb-jar-with-dependencies.jar node-1 localhost 50051 $PEERS
java -jar target\atlasdb-jar-with-dependencies.jar node-2 localhost 50052 $PEERS
```

### Docker-compatible example:
```dockerfile
ENV ATLAS_NODE_ID=node-0
ENV ATLAS_HOST=0.0.0.0
ENV ATLAS_PORT=50050
ENV ATLAS_PEERS=node-0:node0:50050,node-1:node1:50050,node-2:node2:50050
CMD ["java", "-jar", "atlasdb.jar"]
```

---

## Smart Client — Usage (Phase 4)

```java
List<String> seeds = List.of("localhost:50050", "localhost:50051", "localhost:50052");

try (AtlasClient client = new AtlasClient(seeds)) {
    // Single write (auto-routed to leader)
    client.put("name", "AtlasDB");

    // Read
    String val = client.get("name");   // "AtlasDB"
    String nil = client.get("ghost");  // null

    // Delete
    client.delete("name");             // true

    // Batch write — 10 pairs in 1 RPC, pipelined through Raft in parallel
    Map<String, String> batch = new LinkedHashMap<>();
    for (int i = 1; i <= 10; i++) batch.put("key" + i, "val" + i);
    int committed = client.batchPut(batch); // returns count of committed pairs
}
```

### Run the demo:
```powershell
# Start cluster first (Option A or B above), then:
java -cp target\atlasdb-jar-with-dependencies.jar org.ds.AtlasClientDemo
```

---

## What Each Node Does

| Component             | Description |
|-----------------------|-------------|
| gRPC server           | Listens for Raft RPCs (AppendEntries, RequestVote, InstallSnapshot) and client RPCs (Put, Get, Delete, BatchPut, GetLeader) |
| RaftElectionTimer     | Fires every 200–600ms (randomized) if no heartbeat received; triggers election |
| HeartbeatSender       | Leader sends AppendEntries (empty) to all peers every 100ms |
| Replicator            | Sends vote requests, replicates log entries, triggers InstallSnapshot for lagging followers |
| StateMachine          | Deterministic KV store backed by LSMEngine; applies committed Raft log entries |
| LSMEngine             | MemTable (4MB write buffer) + immutable SSTables on disk |
| WriteAheadLog         | CRC32-framed, fdatasync'd; replayed on crash recovery |
| RaftMetadataStore     | Persists currentTerm + votedFor atomically before every RPC response |
| Snapshotter           | Serializes full state machine state; used for WAL truncation and InstallSnapshot |

---

## What Inputs & Outputs Look Like

### Node startup output (in `logs/node-0.log`):
```
[17:54:05] [INFO   ] AtlasDB NodeLauncher — Phase 3 Multi-Process
[17:54:05] [INFO   ] nodeId = node-0  |  address = localhost:50050
[17:54:05] [INFO   ] [LSM] Recovery complete: 2 SSTables loaded
[17:54:05] [INFO   ] [WAL] Recovered 3 entries from raft.wal
[17:54:05] [INFO   ] [node-0] Recovery done: term=5 log=3 lastApplied=2
[17:54:05] [INFO   ] [node-0] Initialized — election timer armed
[17:54:06] [INFO   ] [node-0] ★★★ BECAME LEADER for term 6 ★★★
[17:54:06] [INFO   ] [Replicator] Leader state init: nextIndex=4 for 2 peers
```

### Client operation output (AtlasClientDemo):
```
── 1. Single Writes ──────────────────────────────────────────────
  PUT  name                 = AtlasDB               →  OK
  PUT  version              = 3.0                   →  OK

── 3. Batch Put (10 pairs → 1 RPC) ──────────────────────────────
  BatchPut: 10/10 committed in 47 ms

── 2. Reads ──────────────────────────────────────────────────────
  GET  name                 →  AtlasDB
  GET  missing_key          →  (nil)

── 4. Delete ─────────────────────────────────────────────────────
  DEL  version              →  deleted
  GET  version              →  (nil)
```

### Disk layout after running:
```
data/
  node-0/
    meta.bin        # 14 bytes — currentTerm(4B) + votedFor string
    raft.wal        # CRC32-framed log entries
    snapshot.bin    # serialized KV snapshot (after takeSnapshot())
    lsm/
      sst_0000.sst  # immutable SSTable files (after MemTable flush)
      sst_0001.sst
```

---

## Phases Summary

| Phase | Feature | Status |
|-------|---------|--------|
| 1 | Raft consensus (leader election, log replication, quorum commit) | ✅ Done |
| 2 | LSM-tree (MemTable+SSTable+BloomFilter), WAL, crash recovery, snapshots | ✅ Done |
| 3 | InstallSnapshot RPC, nextIndex/matchIndex tracking, multi-process NodeLauncher, fat JAR, PowerShell scripts | ✅ Done |
| 4 | Smart client (leader cache, auto-redirect, discovery), BatchPut RPC (parallel Raft pipeline), GetLeader RPC | ✅ Done |
| 5 | Background compaction, ReadIndex linearizable reads, benchmarking | 🔜 Planned |

---

## Resetting State

To start completely fresh (wipe all persisted data):
```powershell
.\scripts\stop-cluster.ps1
Remove-Item -Recurse -Force data\
```

---

## Build Commands Reference

```powershell
# Compile only
mvn compile

# Compile + build fat JAR
mvn package -DskipTests

# In-process demo (no JAR needed)
mvn "-Dexec.mainClass=org.ds.Server" exec:java

# Run smart client demo (cluster must be running)
java -cp target\atlasdb-jar-with-dependencies.jar org.ds.AtlasClientDemo
```
