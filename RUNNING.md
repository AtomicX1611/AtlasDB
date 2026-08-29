# AtlasDB — How to Run

> **Java · gRPC · Raft Consensus · LSM-Tree Persistence**

---

## Prerequisites

| Tool        | Version  | Check with          |
|-------------|----------|---------------------|
| JDK         | 21+      | `java -version`     |
| Maven       | 3.9+     | `mvn -version`      |
| grpcurl     | any      | `grpcurl --version` |

grpcurl install (optional — for manual testing):
```
# Windows (Scoop)
scoop install grpcurl

# macOS
brew install grpcurl
```

---

## Build

```powershell
cd d:\Documents\Atlas_DB
mvn clean compile
```

Expected output: `BUILD SUCCESS` — compiles ~40 source files + generates protobuf stubs.

---

## Run

### Single command — boots a full 3-node cluster in one JVM process

```powershell
mvn "-Dexec.mainClass=org.ds.Server" exec:java
```

### What happens (chronologically)

```
1. Three gRPC servers start on ports 50050, 50051, 50052
2. Each node arms a randomized election timer (150–300 ms)
3. First node to time out starts an election (becomes CANDIDATE, increments term)
4. Collects votes from 2 out of 3 nodes → wins → becomes LEADER
5. Leader starts sending heartbeats every 100 ms to suppress follower elections
6. Demo writes 3 entries through Raft consensus:
     SET x 10      → replicated to both followers, then committed
     SET y 20      → same
     SET name AtlasDB
7. Reads back from leader's state machine → prints values
8. Cluster stays live; you can send gRPC requests from another terminal
```

---

## Node Layout (3-node default)

| Node ID | Port  | Role at Start | gRPC Services Exposed            |
|---------|-------|---------------|----------------------------------|
| node-0  | 50050 | FOLLOWER      | RaftService + AtlasService       |
| node-1  | 50051 | FOLLOWER      | RaftService + AtlasService       |
| node-2  | 50052 | FOLLOWER      | RaftService + AtlasService       |

> After election, exactly one node becomes LEADER. **Any node can receive client requests** — non-leaders return `NOT_LEADER` plus a `leader_hint` (the node ID of the current leader) so the client can redirect.

---

## Process Count for N Nodes

| Nodes | JVM Processes | Ports Used                    | Majority Needed |
|-------|---------------|-------------------------------|-----------------|
| 1     | 1             | 50050                         | 1 (trivial)     |
| 3     | 1*            | 50050–50052                   | 2               |
| 5     | 1*            | 50050–50054                   | 3               |

> *Currently all nodes run in one JVM (`Cluster.java`). For true multi-process deployment, run one JVM per node with `NodeLauncher.java` (Docker-ready, Phase 3).

### To change node count

Edit `Server.java`, line:
```java
Cluster cluster = new Cluster(3); // change 3 to any odd number ≥ 1
```

---

## Inputs / Outputs

### Internal Raft RPCs (node ↔ node)
These fire automatically. You don't call these directly.

| RPC            | Direction         | Trigger                    |
|----------------|-------------------|----------------------------|
| `RequestVote`  | Candidate → Peers | Election timeout fires      |
| `AppendEntries`| Leader → Followers| Heartbeat (empty) or write  |

### Client API (`AtlasService`) — your application calls these

**Put (write a key-value pair)**
```bash
grpcurl -plaintext \
  -d '{"key": "user", "value": "alice"}' \
  localhost:50050 \
  org.ds.proto.AtlasService/Put
```
Response (success):
```json
{ "success": true }
```
Response (hit a follower):
```json
{ "success": false, "error": "NOT_LEADER", "leaderHint": "node-0" }
```

**Get (read a value)**
```bash
grpcurl -plaintext \
  -d '{"key": "user"}' \
  localhost:50050 \
  org.ds.proto.AtlasService/Get
```
Response:
```json
{ "value": "alice", "found": true }
```
Key not found:
```json
{ "value": "", "found": false }
```

**Delete**
```bash
grpcurl -plaintext \
  -d '{"key": "user"}' \
  localhost:50050 \
  org.ds.proto.AtlasService/Delete
```
Response:
```json
{ "deleted": true }
```

---

## On-Disk Data (Phase 2 — Persistent Storage)

After running, the following files are created under `data/` in the project root:

```
data/
├── node-0/
│   ├── meta.bin          ← Raft metadata: currentTerm + votedFor
│   ├── raft.wal          ← Write-Ahead Log: every Raft log entry (CRC32-framed)
│   ├── snapshot.bin      ← State machine snapshot (created every 50 commits)
│   └── lsm/
│       ├── sst-000001.sst ← Immutable SSTable (sorted KV pairs)
│       ├── sst-000002.sst ← Newer SSTable (written when MemTable exceeds 4 MB)
│       └── ...
├── node-1/
│   └── (same structure)
└── node-2/
    └── (same structure)
```

### WAL Record Format
```
[MAGIC: 4B][CRC32: 4B][LEN: 4B][PAYLOAD: LEN bytes]
PAYLOAD = [index: 4B][term: 4B][cmdLen: 4B][command: UTF-8]
```

### SSTable File Layout
```
[Data Section]  sorted key-value pairs
[Index Section] sparse index: every 16th key → file offset (binary search)
[Bloom Filter]  probabilistic membership test (1% false positive rate)
[Footer: 16B]   offsets of index + bloom sections
```

---

## Crash Recovery (Phase 2)

If you kill the process (`Ctrl+C`) and restart, each node:
1. Reads `meta.bin` → restores `currentTerm` and `votedFor` (safety: no double-votes)
2. Reads `snapshot.bin` → restores state machine to last snapshot
3. Replays `raft.wal` from `snapshot.lastIndex + 1` → rebuilds in-memory `raftLog`
4. Opens all `*.sst` files → restores LSM read layer

The cluster re-elects a new leader within 150–300 ms.

---

## Observing the Election

Watch the terminal for these log lines:

```
[node-1] Election timeout fired — starting election
[node-1] ── Starting election for term 1 ──
[node-0] Granted vote to node-1 for term 1
[node-2] Granted vote to node-1 for term 1
[node-1] ★★★ BECAME LEADER for term 2 ★★★
[node-1] Heartbeat sender STARTED
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                     Client (grpcurl / app)              │
└───────────────────────┬─────────────────────────────────┘
                        │ AtlasService (Put/Get/Delete)
                        ▼
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│   node-0    │   │   node-1    │   │   node-2    │
│  :50050     │   │  :50051     │   │  :50052     │
│  [LEADER]   │   │  [FOLLOWER] │   │  [FOLLOWER] │
│             │◄──┤             │   │             │
│  Raft log   │──►│  Raft log   │   │  Raft log   │
│  meta.bin   │   │  meta.bin   │   │  meta.bin   │
│  raft.wal   │   │  raft.wal   │   │  raft.wal   │
│  snapshot   │   │  snapshot   │   │  snapshot   │
│  LSM-Tree   │   │  LSM-Tree   │   │  LSM-Tree   │
│  (MemTable) │   │  (MemTable) │   │  (MemTable) │
│  (SSTable)  │   │  (SSTable)  │   │  (SSTable)  │
└─────────────┘   └─────────────┘   └─────────────┘
         AppendEntries (gRPC) ────────────────────►
         ◄──────────────────── RequestVote (gRPC)
```

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Port already in use: 50050` | Previous run still alive | Kill previous process |
| `No leader elected within 4s` | All 3 nodes timed out simultaneously (split vote storm) | Re-run; randomized timers prevent this in practice |
| `data/` directory missing | First run | Created automatically on startup |
| grpcurl `Unavailable` | Hit a follower, or cluster not started | Check `leaderHint` in response, use that port |

---

## Clean Slate (delete all persistent data)

```powershell
Remove-Item -Recurse -Force .\data\
```

---

## Phase Roadmap

| Phase | Status | Feature |
|-------|--------|---------|
| 1     | ✅ Done | Raft election, quorum commit, KV state machine |
| 2     | ✅ Done | WAL, LSM-tree (MemTable + SSTable + Bloom filter), crash recovery, snapshots |
| 3     | 🔜 Next | Multi-process deployment (`NodeLauncher`), InstallSnapshot RPC |
| 4     | 🔜      | Smart client (auto-redirect), batch writes |
| 5     | 🔜      | Background compaction, ReadIndex linearizable reads, benchmarks |
