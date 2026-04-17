# CS553 Distributed Algorithms Simulator
**Student:** Shivam Moudgil | **UIN:** 665599956  
**Course:** CS553 — Distributed Computing Systems, Spring 2026  
**Algorithms:** Wave Algorithm (Index 13) · Lai-Yang Snapshot (Index 23)

---

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Architecture](#architecture)
5. [Key Source Files](#key-source-files)
6. [Configuration](#configuration)
7. [Building the Project](#building-the-project)
8. [Generating Graphs](#generating-graphs)
9. [Running Experiments](#running-experiments)
10. [All Run Commands](#all-run-commands)
11. [Running Tests](#running-tests)
12. [Algorithms](#algorithms)
13. [Metrics & Observability](#metrics--observability)
14. [Cinnamon Instrumentation](#cinnamon-instrumentation)
15. [Design Decisions](#design-decisions)

---

## Overview

This project implements an end-to-end distributed algorithms simulator that:

1. Loads randomly generated graphs from **NetGameSim** (`.ngs` files)
2. Enriches the graph with **edge labels** (which message types are allowed per channel) and **node PDFs** (traffic generation probabilities)
3. Converts each graph node into an **Akka Classic actor** — one actor per node, one ActorRef per directed edge
4. Runs configurable **background traffic** driven by per-node probability distributions
5. Executes two **distributed algorithms** (Wave + Lai-Yang Snapshot) as pluggable modules on top of the same actor runtime
6. Produces **metrics, logs, and results** as observable output

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 17 (required) | Must be exactly Java 17 for Akka compatibility |
| sbt | 1.9.7 | Scala Build Tool |
| Scala | 3.3.1 | Project language |
| Git | Any | For submodule checkout |

### Verify Java 17

```bash
java -version
# Should show: openjdk version "17.x.x"
```

### Switch to Java 17 on macOS

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH=$JAVA_HOME/bin:$PATH
java -version  # confirm 17
```

Add the above two lines to your `~/.zshrc` or `~/.bashrc` to make it permanent.

---

## Project Structure

```
CS553_2026/
├── netgamesim/                          # NetGameSim submodule (graph generator)
│   └── target/scala-3.2.2/
│       └── netmodelsim.jar              # Pre-built JAR used by this project
│
├── src/
│   ├── main/
│   │   ├── resources/
│   │   │   ├── application.conf         # All simulation parameters (no hardcoding)
│   │   │   ├── logback.xml              # Logging configuration
│   │   │   ├── inject.txt               # File-driven injection script
│   │   │   └── graphs/
│   │   │       ├── SmallGraph.ngs       # 21 nodes, 20 edges  (Experiment 1)
│   │   │       ├── MediumGraph.ngs      # 51 nodes, 54 edges  (Experiment 2)
│   │   │       └── LargeGraph.ngs       # 101 nodes, 109 edges (Experiment 3)
│   │   └── scala/com/uic/cs553/distributed/
│   │       ├── algorithms/
│   │       │   ├── WaveAlgorithm.scala       # Wave algorithm (Index 13)
│   │       │   ├── LaiYangSnapshot.scala     # Lai-Yang snapshot (Index 23)
│   │       │   ├── AlgorithmRegistry.scala   # Config-driven algorithm loader
│   │       │   ├── WaveMessages.scala        # Wave protocol messages
│   │       │   ├── LaiYangMessages.scala     # Lai-Yang protocol messages
│   │       │   ├── EchoAlgorithm.scala       # Prof-provided example
│   │       │   ├── BullyLeaderElection.scala # Prof-provided example
│   │       │   └── TokenRingAlgorithm.scala  # Prof-provided example
│   │       ├── framework/
│   │       │   ├── DistributedNode.scala     # Prof-provided base framework
│   │       │   └── ExperimentRunner.scala    # Prof-provided experiment runner
│   │       ├── simcli/
│   │       │   ├── SimMain.scala             # Main entry point
│   │       │   ├── FileDrivenInjector.scala  # --inject mode
│   │       │   ├── InteractiveInjector.scala # --interactive mode
│   │       │   └── InjectionEntry.scala      # Injection data model
│   │       ├── simcore/
│   │       │   ├── SimConfig.scala           # Config loading (Typesafe Config)
│   │       │   ├── SimGraph.scala            # Graph model (SimGraph, SimEdge, EnrichedGraph)
│   │       │   ├── EdgeLabel.scala           # Per-edge message type constraints
│   │       │   ├── NodePdf.scala             # Per-node traffic distribution
│   │       │   ├── PdfSampler.scala          # Seeded random sampler
│   │       │   ├── GraphLoader.scala         # Deserializes .ngs files via NetGameSim
│   │       │   ├── GraphEnricher.scala       # Attaches labels + PDFs to graph
│   │       │   ├── GraphSerializer.scala     # JSON save/load for EnrichedGraph
│   │       │   └── MessageType.scala         # CONTROL | PING | GOSSIP | WORK | ACK
│   │       └── simruntimeakka/
│   │           ├── NodeActor.scala           # Core actor (one per graph node)
│   │           ├── NodeContext.scala         # Algorithm dependency injection
│   │           ├── DistributedAlgorithm.scala # Plugin trait for algorithms
│   │           ├── MetricsCollector.scala    # Thread-safe metrics (TrieMap + AtomicLong)
│   │           ├── GraphToActorMapper.scala  # Graph → ActorSystem wiring
│   │           └── ActorSystemBootstrap.scala # System lifecycle management
│   └── test/
│       └── scala/com/uic/cs553/distributed/framework/
│           ├── EdgeLabelEnforcementTest.scala
│           ├── PdfSamplerTest.scala
│           ├── SimGraphTest.scala
│           ├── WaveAlgorithmTest.scala
│           ├── NodeActorTest.scala
│           ├── DistributedNodeSpec.scala
│           ├── LaiYangSnapshotTest.scala
│           ├── GraphEnricherSerializerTest.scala
│           ├── FileDrivenInjectorTest.scala
│           └── GraphToActorMapperTest.scala
│
├── docs/
│   └── report.md                        # Design decisions + experiment results
│
├── build.sbt                            # Build definition + Cinnamon plugin
├── project/
│   └── plugins.sbt                      # sbt plugins (assembly, Cinnamon)
├── .gitignore
├── .gitmodules                          # netgamesim submodule reference
└── README.md                           # This file
```

---

## Architecture

```
NetGameSim (.ngs file)
        │
        ▼
   GraphLoader          ← Deserializes binary .ngs via Java ObjectInputStream
        │
        ▼
    SimGraph             ← nodes: Set[Int], edges: Set[SimEdge]
        │
        ▼
  GraphEnricher          ← Reads application.conf for edge labels + node PDFs
        │
        ▼
  EnrichedGraph          ← edgeLabels: Map[(Int,Int), EdgeLabel]
        │                   nodePdfs:  Map[Int, NodePdf]
        ▼
GraphToActorMapper       ← Creates one NodeActor per node, wires ActorRefs
        │
        ▼
  ActorSystem            ← "sim" Akka Classic ActorSystem
  ┌─────────────────────────────────────────┐
  │  NodeActor(0) ──CONTROL──▶ NodeActor(35) │
  │  NodeActor(1) ──WORK────▶ NodeActor(20)  │
  │  NodeActor(35)──PING────▶ NodeActor(9)   │
  │  ... (one actor per node, edges = refs)  │
  └─────────────────────────────────────────┘
        │
        ▼
  Algorithm Plugins      ← WaveAlgorithm + LaiYangSnapshot run on same actors
        │
        ▼
  MetricsCollector       ← Sent/dropped per message type, final report
```

### Key Design Principles

- **One actor per node** — not router pools. Each actor owns independent state, preventing data inconsistency.
- **Edge labels enforced at send time** — `NodeActor.sendToEligibleNeighbor()` checks `allowedOnEdge` before every send. Forbidden messages are dropped and counted.
- **Immutable state via `context.become`** — `NodeActor` uses `context.become(initialized(...))` to pass state as parameters rather than `var` fields.
- **Algorithm plugin pattern** — algorithms implement `DistributedAlgorithm` trait (`onStart`, `onMessage`, `onTick`) and are injected into actors at boot time.
- **Config-driven** — all parameters (graph path, seed, duration, PDFs, timers, algorithms) come from `application.conf`. Nothing is hardcoded.

---

## Key Source Files

### `NodeActor.scala`
The core simulation actor. Uses `context.become` for immutable state transitions. Handles `Init`, `Tick`, `Envelope`, `ExternalInput`, `AlgorithmMsg`, and `Stop` messages. Enforces edge labels on every send.

### `WaveAlgorithm.scala`
Echo-algorithm variant of the Wave algorithm. Initiator sends WAVE to all neighbors. Internal nodes forward and collect ACKs. Leaf nodes ACK immediately. Initiator decides when all ACKs received. Uses immutable `WaveState` case class.

### `LaiYangSnapshot.scala`
Snapshot for NON-FIFO channels. Uses RED/WHITE coloring. Initiator turns RED, sends `SNAPSHOT_MARKER` to all neighbors. WHITE nodes receiving a marker turn RED and record local state. WHITE messages arriving at RED nodes are captured as in-flight channel state. Uses immutable `SnapState` case class.

### `GraphEnricher.scala`
Reads `edgeLabeling.overrides` and `defaultEdgeLabel` from config. Always adds `CONTROL` to every edge (algorithm messages must pass). Reads `perNodePdf` overrides and `defaultPdf` for node traffic profiles.

### `MetricsCollector.scala`
Thread-safe metrics using `TrieMap[key, AtomicLong]`. Tracks sent/received/dropped per `(node, type)`. Final report printed on simulation end.

### `SimConfig.scala`
Wraps Typesafe Config. Exposes `withOverrides(graphFile, runSecs)` for CLI overrides. `fromFile(path)` loads from explicit path. All parameters config-driven.

---

## Configuration

All simulation parameters live in `src/main/resources/application.conf`:

```hocon
sim {
  # Graph file — relative path from project root
  graphFile = "src/main/resources/graphs/LargeGraph.ngs"
  seed = 999                    # Random seed for reproducibility
  runDurationSeconds = 120      # How long to run

  messages {
    types = [ "CONTROL", "PING", "GOSSIP", "WORK", "ACK" ]
  }

  edgeLabeling {
    default = [ "CONTROL", "PING", "GOSSIP", "WORK" ]
    overrides = [
      # Restrict specific edges to fewer message types
      { from = 0, to = 35, allow = [ "CONTROL", "PING", "WORK" ] },
      { from = 1, to = 20, allow = [ "CONTROL", "GOSSIP", "WORK" ] },
      { from = 35, to = 9, allow = [ "CONTROL", "WORK" ] }
    ]
  }

  traffic {
    tickIntervalMs = 50
    distribution = "zipf"
    defaultPdf = [
      { msg = "PING",   p = 0.40 },
      { msg = "GOSSIP", p = 0.35 },
      { msg = "WORK",   p = 0.25 }
    ]
    # Per-node overrides — specific nodes generate different traffic
    perNodePdf = [
      { node = 0,  pdf = [ { msg = "WORK", p = 0.70 }, { msg = "PING", p = 0.20 }, { msg = "GOSSIP", p = 0.10 } ] },
      { node = 1,  pdf = [ { msg = "GOSSIP", p = 0.60 }, { msg = "PING", p = 0.30 }, { msg = "WORK", p = 0.10 } ] },
      { node = 35, pdf = [ { msg = "WORK", p = 0.80 }, { msg = "PING", p = 0.20 } ] }
    ]
  }

  initiators {
    timers = [
      { node = 0, tickEveryMs = 50,  mode = "pdf" },          # PDF-driven timer
      { node = 1, tickEveryMs = 75,  mode = "fixed", fixedMsg = "GOSSIP" }  # Fixed-type timer
    ]
    inputs = [
      { node = 2 }    # Input node — accepts external injection
    ]
  }

  algorithms {
    initiatorNode  = 0
    enableWave     = true
    enableLaiYang  = true
  }
}
```

### Override at runtime via CLI flags

```bash
# Override graph and duration without editing application.conf
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s"
```

---

## Building the Project

### 1. Clone with submodules

```bash
git clone --recurse-submodules https://github.com/its-Shivam732/CS553-DistributedAlgorithms.git
cd CS553-DistributedAlgorithms
```

Or if already cloned without submodules:
```bash
git submodule update --init --recursive
```

### 2. Set Java 17

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH=$JAVA_HOME/bin:$PATH
java -version   # Must show 17
```

### 3. Compile

```bash
sbt compile
```

Expected output: `[success] Total time: ...`

---

## Generating Graphs

Graphs are pre-generated and included in `src/main/resources/graphs/`. NetGameSim generates `.ngs` binary files via Java serialization.

### Pre-included graphs

| File | Nodes | Edges | Used in |
|------|-------|-------|---------|
| `SmallGraph.ngs` | 21 | 20 | Experiment 1 |
| `MediumGraph.ngs` | 51 | 54 | Experiment 2 |
| `LargeGraph.ngs` | 101 | 109 | Experiment 3 |

### To regenerate graphs (optional)

```bash
cd netgamesim
sbt run
# Graphs are written to netgamesim/target/ or configured output directory
# Copy .ngs files to src/main/resources/graphs/
```

### How graph loading works

`GraphLoader.loadFromPath(path)` uses a custom `ObjectInputStream` with NetGameSim's classloader to deserialize `List[NetGraphComponent]`. Nodes become `Set[Int]` (using `NodeObject.id`), edges become `Set[SimEdge]`.

---

## Running Experiments

### Experiment 1 — Wave Algorithm, SmallGraph, 30 seconds

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH=$JAVA_HOME/bin:$PATH

sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s \
  --wave-only"
```

**Expected output:**
- 21 actors initialized
- Wave propagation: `Node 0: sending wave ... to neighbors`
- GOSSIP messages dropped (edge label enforcement)
- Metrics: ~532 sent, ~210 GOSSIP dropped

### Experiment 2 — Lai-Yang Snapshot, MediumGraph, 60 seconds

```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/MediumGraph.ngs \
  --run 60s \
  --snapshot-only"
```

**Expected output:**
- 51 actors initialized
- `Node 0: initiating snapshot`
- `Node 0: recorded local state at snapshot <id>`
- `Node 0: turned RED, sending markers to Set(35, 34)`
- Metrics: ~2,544 sent, 0 dropped

### Experiment 3 — Both Algorithms, LargeGraph, 120 seconds

```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/LargeGraph.ngs \
  --run 120s"
```

**Expected output:**
- 101 actors initialized
- Both Wave and Lai-Yang running simultaneously
- Metrics: ~5,036 sent, ~7 WORK dropped

### Experiment Summary

| # | Graph | Algorithm | Sent | Dropped | Duration |
|---|-------|-----------|------|---------|----------|
| 1 | SmallGraph (21n/20e) | Wave only | ~532 | ~210 GOSSIP | 30s |
| 2 | MediumGraph (51n/54e) | Lai-Yang only | ~2,544 | 0 | 60s |
| 3 | LargeGraph (101n/109e) | Both | ~5,036 | ~7 WORK | 120s |

---

## All Run Commands

```bash
# Set Java 17 (required before any sbt command)
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH=$JAVA_HOME/bin:$PATH

# Default run — LargeGraph, 120s, both algorithms (from application.conf)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain"

# With explicit config file
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --config src/main/resources/application.conf"

# Override graph and duration
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s"

# Wave algorithm only
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s \
  --wave-only"

# Lai-Yang snapshot only
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/MediumGraph.ngs \
  --run 60s \
  --snapshot-only"

# Both algorithms (explicit)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/LargeGraph.ngs \
  --run 120s"

# Traffic only (no algorithms)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s \
  --no-algorithms"

# File-driven injection
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --inject src/main/resources/inject.txt"

# Interactive injection (type commands in terminal)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --interactive"
# Then type: node=1 kind=WORK payload=my-task
#            node=2 kind=PING payload=probe
#            quit

# Run tests
sbt test
```

### Injection file format (`inject.txt`)

```
# Format: delayMs=<ms>,node=<id>,kind=<TYPE>,payload=<text>
delayMs=100,node=1,kind=WORK,payload=task-001
delayMs=200,node=2,kind=PING,payload=probe-001
delayMs=500,node=1,kind=GOSSIP,payload=update-001
delayMs=1000,node=2,kind=WORK,payload=task-002
```

---

## Running Tests

```bash
sbt test
```

### Test files (10 files, 40+ assertions)

| Test File | What it tests |
|-----------|--------------|
| `EdgeLabelEnforcementTest` | EdgeLabel allows/blocks correct message types |
| `PdfSamplerTest` | PDF sampling, reproducibility under seed, Zipf distribution |
| `SimGraphTest` | Graph topology operations (neighbors, hasEdge, node/edge count) |
| `WaveAlgorithmTest` | Wave initiator sends on start, AlgorithmRegistry flags |
| `NodeActorTest` | Actor init, ExternalInput forwarding, edge label blocking, metrics |
| `DistributedNodeSpec` | Prof-provided framework base class tests |
| `LaiYangSnapshotTest` | Snapshot initiator sends CONTROL, non-initiator stays quiet |
| `GraphEnricherSerializerTest` | Enrich assigns labels/PDFs, round-trip JSON serialize/deserialize |
| `FileDrivenInjectorTest` | Parse injection scripts, skip comments, inject correct message types |
| `GraphToActorMapperTest` | Creates correct number of actors, all ActorRefs distinct and live |

---

## Algorithms

### Wave Algorithm (Index 13)

**Variant:** Echo Algorithm on general directed graph  
**System assumptions:**
- Asynchronous message passing
- Reliable channels (no message loss)
- General graph topology (not restricted to ring or tree)
- No process failures

**Protocol:**
1. Initiator (Node 0) sends `WAVE:<id>:<from>` (as CONTROL payload) to all neighbors
2. IDLE nodes receiving Wave become ACTIVE, forward to all neighbors except parent
3. Leaf nodes immediately send `WAVEACK:<id>:<from>` back to parent
4. Nodes collect ACKs from all children; when complete, send ACK to parent
5. Initiator decides when all ACKs received — wave complete

**State model:** Immutable `WaveState(phase, parent, pendingAcks, waveId, startTime)` case class. Transitions via `.copy()`.

### Lai-Yang Snapshot Algorithm (Index 23)

**Variant:** Standard Lai-Yang for NON-FIFO channels  
**System assumptions:**
- NON-FIFO asynchronous channels (stronger than Chandy-Lamport)
- Messages carry color of sender at send time
- No process failures during snapshot
- General graph topology

**Protocol (RED/WHITE coloring):**
1. Initiator turns RED, records local state, sends `SNAPSHOT_MARKER:<id>:<from>` to all neighbors
2. WHITE node receiving first marker: turns RED, records state, sends markers to all neighbors
3. RED node receiving any message: if sender was WHITE, message is in-flight → captured as channel state
4. Snapshot complete when node is RED and `pendingRed == 0`

**State model:** Immutable `SnapState(color, snapshotId, localState, inFlightMessages, pendingRed, ...)` case class. Transitions via `.copy()`.

---

## Metrics & Observability

Every simulation run prints a final metrics report:

```
==========================================
=== SIMULATION METRICS FINAL REPORT  ===
==========================================
Total messages sent:    5036
Total messages dropped: 7
  CONTROL → sent: 4,   dropped: 0
  PING    → sent: 1580, dropped: 0
  GOSSIP  → sent: 1401, dropped: 0
  WORK    → sent: 2051, dropped: 7
==========================================
```

**Metrics are collected via:**
- `MetricsCollector.recordSent(from, to, kind)` — called in `NodeActor.sendToEligibleNeighbor`
- `MetricsCollector.recordDropped(from, -1, kind)` — called when no eligible neighbor found
- `MetricsCollector.recordReceived(nodeId, kind)` — called on every received Envelope

All counters use `TrieMap` + `AtomicLong` for thread-safe concurrent updates.

**Logging levels:**
- `INFO` — node init, algorithm milestones, every sent message, metrics report
- `DEBUG` — dropped messages, work queue processing
- `WARN` — missing neighbors, edge constraint violations in NodeContext

---

## Cinnamon Instrumentation

The Lightbend Cinnamon sbt plugin (v2.21.4) is configured in `project/plugins.sbt`:

```scala
addSbtPlugin("com.lightbend.cinnamon" % "sbt-cinnamon" % "2.21.4")
```

The actor instrumentation configuration is in `application.conf`:

```hocon
cinnamon {
  akka.actors = {
    default-by-class {
      includes = "/user/*"
      report-by = class
    }
  }
}
```

**Note:** Full Cinnamon instrumentation requires a Lightbend commercial license/token (available at `account.akka.io/token`). The plugin and configuration are present and correctly structured. To activate with a token, add the resolver to `build.sbt`:

```scala
resolvers += "Akka library repository".at(
  "https://repo.akka.io/<YOUR_TOKEN>/secure"
)
```

And restore the Cinnamon library dependencies:
```scala
Cinnamon.library.cinnamonAkka,
Cinnamon.library.cinnamonCHMetrics,
Cinnamon.library.cinnamonJvmMetricsProducer
```

---

## Design Decisions

### Why Akka Classic (not Typed)?
The professor's provided framework (`DistributedNode`, `ExperimentRunner`) uses Akka Typed. The simulation runtime uses Akka Classic because `context.become` is more natural for the state machine patterns required by Wave and Lai-Yang algorithms. Both APIs are available on the classpath.

### Why one actor per node (not router pools)?
Each graph node has independent algorithm state (wave phase, snapshot color). Router pools share state across instances, which would cause data inconsistency in distributed algorithm execution.

### Why `context.become` instead of `var`?
The grading rubric deducts 0.3% per `var` used for heap-based shared state. `NodeActor` uses `context.become(initialized(neighbors, pdf, ctx, workQueue))` to pass all mutable state as immutable function parameters, avoiding `var` entirely for the actor's operational state.

### Why single `var state` in algorithm modules?
`DistributedAlgorithm` is a plain trait, not an actor. It cannot use `context.become`. The compromise is one `private var state: AlgorithmState` holding an immutable case class, with all transitions via `.copy()`. This is explicitly justified in comments per the rubric.

### Why CONTROL type always allowed on all edges?
Algorithm protocol messages (wave markers, snapshot markers) must propagate regardless of application-level edge restrictions. Adding CONTROL to every edge in `GraphEnricher.buildEdgeLabels` ensures algorithm correctness without requiring special cases in NodeActor.

### Reproducibility
All experiments use fixed seeds (`seed = 999` in application.conf). `PdfSampler(seed)` wraps `scala.util.Random(seed)` ensuring identical message sequences across runs. Pass `--run` and `--graph` CLI flags to reproduce any experiment configuration.

---

## Versions

| Component | Version |
|-----------|---------|
| Scala | 3.3.1 |
| Akka Classic | 2.8.5 |
| Akka Typed | 2.8.5 |
| sbt | 1.9.7 |
| Java | 17 (required) |
| ScalaTest | 3.2.17 |
| Logback | 1.4.11 |
| Circe (JSON) | 0.14.6 |
| Typesafe Config | 1.4.2 |
| Cinnamon plugin | 2.21.4 |
| NetGameSim | Submodule @ 0x1DOCD00D/NetGameSim |
