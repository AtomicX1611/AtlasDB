# Atlas DB

A distributed database system implementing the **Raft consensus protocol** for fault-tolerant log replication using Java 21 and gRPC.

## Architecture

Leader-based replication ensures strong consistency. The leader receives commands and replicates them to followers via `AppendEntries` RPCs.

## Core Components

- **Cluster**: Orchestrates nodes and coordinates replication logic.
- **Node**: Individual server (leader or follower) maintaining a local log.
- **Replicator**: Handles gRPC calls (`AppendEntries`, `RequestVote`) to followers.
- **LogEntry**: Immutable entry containing index, term, and command.
- **RaftServiceImpl**: gRPC service implementing Raft RPC methods.

## Getting Started

### Prerequisites
- **Java 21** or higher
- **Maven 3.6+**

### Build & Run
```bash
git clone https://github.com/yourusername/Atlas_DB.git
cd Atlas_DB

# Compile and generate Proto sources
mvn clean compile

# Run the demo server
mvn exec:java -Dexec.mainClass="org.ds.Server"
```

## Usage Example

Run the `org.ds.Server` class to execute the demo. To change the cluster configuration, modify the node count in [src/main/java/org/ds/Server.java](src/main/java/org/ds/Server.java) (e.g., `new Cluster(5)`).

## Key Features
- **Strong Consistency**: Sequential log replication.
- **gRPC Communication**: High-performance inter-node messaging.
- **Fault Tolerance**: Designed to withstand node failures.

## Project Structure
```
src/main/java/org/ds/
├── Server.java               # Main Entry point
├── Replication/
│   ├── Cluster/Cluster.java  # Cluster management
│   ├── Node/Node.java        # Node state & logic
│   ├── Replicator/           # gRPC client wrappers
│   └── RaftServiceImpl.java  # gRPC service implementation
└── proto/raft.proto          # Protobuf definitions
```

## Tech Stack
- **Languages**: Java 21
- **Frameworks**: gRPC 1.66.0, Protobuf 3.25.2

