# CS553 Distributed Algorithms Simulator
**Youtube video:** https://www.youtube.com/watch?v=FNe1j0h5-68

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
│   │   │   ├── application-exp2.conf    # Strict edge config for Experiment 6
│   │   │   ├── logback.xml              # Logging configuration
│   │   │   ├── inject.txt               # File-driven injection script
│   │   │   └── graphs/
│   │   │       ├── SmallGraph.ngs       # 21 nodes, 20 edges
│   │   │       ├── MediumGraph.ngs      # 51 nodes, 54 edges
│   │   │       └── LargeGraph.ngs       # 101 nodes, 138 edges
│   │   └── scala/com/uic/cs553/distributed/
│   │       ├── algorithms/
│   │       │   ├── WaveAlgorithm.scala       # Wave algorithm (Index 13)
│   │       │   ├── LaiYangSnapshot.scala     # Lai-Yang snapshot (Index 23)
│   │       │   ├── AlgorithmRegistry.scala   # Config-driven algorithm loader
│   │       │   ├── WaveMessages.scala        # Wave protocol messages
│   │       │   └── LaiYangMessages.scala     # Lai-Yang protocol messages
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
│   └── report.md                        # Full design decisions + experiment results
│
├── build.sbt
├── project/plugins.sbt
├── .gitignore
├── .gitmodules
└── README.md
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

---

## Key Source Files

### `NodeActor.scala`
The core simulation actor. Uses `context.become` for immutable state transitions. Handles `Init`, `Tick`, `Envelope`, `ExternalInput`, `AlgorithmMsg`, and `Stop` messages. Enforces edge labels on every send. Implements PING→ACK reply (topology-dependent), GOSSIP hop forwarding, and WORK cascade with 50% probability.

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
  graphFile = "src/main/resources/graphs/LargeGraph.ngs"
  seed = 999
  runDurationSeconds = 120

  messages {
    types = [ "CONTROL", "PING", "GOSSIP", "WORK", "ACK" ]
  }

  edgeLabeling {
    default = [ "CONTROL", "PING", "GOSSIP", "WORK" ]
    overrides = [
      { from = 0,  to = 35, allow = [ "CONTROL", "PING", "WORK" ] },
      { from = 1,  to = 20, allow = [ "CONTROL", "GOSSIP", "WORK" ] },
      { from = 35, to = 9,  allow = [ "CONTROL", "WORK" ] }
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
    perNodePdf = [
      { node = 0,  pdf = [ { msg = "WORK", p = 0.70 }, { msg = "PING", p = 0.20 }, { msg = "GOSSIP", p = 0.10 } ] },
      { node = 1,  pdf = [ { msg = "GOSSIP", p = 0.60 }, { msg = "PING", p = 0.30 }, { msg = "WORK", p = 0.10 } ] },
      { node = 35, pdf = [ { msg = "WORK", p = 0.80 }, { msg = "PING", p = 0.20 } ] }
    ]
  }

  initiators {
    timers = [
      { node = 0, tickEveryMs = 50,  mode = "pdf" },
      { node = 1, tickEveryMs = 75,  mode = "fixed", fixedMsg = "GOSSIP" }
    ]
    inputs = [{ node = 2 }]
  }

  algorithms {
    initiatorNode = 0
    enableWave    = true
    enableLaiYang = true
  }
}
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

---

## Generating Graphs

Graphs are pre-generated and included in `src/main/resources/graphs/`.

| File | Nodes | Edges |
|------|-------|-------|
| `SmallGraph.ngs` | 21 | 20 |
| `MediumGraph.ngs` | 51 | 54 |
| `LargeGraph.ngs` | 101 | 138 |

All graphs are directed and asymmetric. To regenerate:

```bash
cd netgamesim && sbt run
# Copy output .ngs files to src/main/resources/graphs/
```

---

## Running Experiments

Six experiments are documented in `docs/report.md` with full results. Commands below — all must be run as single lines in zsh:

```bash
# Experiment 1 — No-algorithm baseline (SmallGraph, 5s)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 5s --no-algorithms --save-graph outputs/smallgraph-enriched.json" 2>&1 | tee outputs/no-algo-small.log

# Experiment 2 — Wave algorithm (SmallGraph, 30s)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s --wave-only --save-graph outputs/smallgraph-enriched.json" 2>&1 | tee outputs/exp1-wave-small.log

# Experiment 3 — Lai-Yang snapshot (MediumGraph, 60s)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/MediumGraph.ngs --run 60s --snapshot-only --save-graph outputs/mediumgraph-enriched.json" 2>&1 | tee outputs/exp2-snapshot-medium.log

# Experiment 4 — Both algorithms (LargeGraph, 120s)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 120s --save-graph outputs/largegraph-enriched.json" 2>&1 | tee outputs/exp3-both-large.log

# Experiment 5 — File-driven injection (LargeGraph, 30s)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 30s --inject src/main/resources/inject.txt" 2>&1 | tee outputs/exp4-injection.log

# Experiment 6 — Strict config override (SmallGraph, 30s, both algorithms)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --config src/main/resources/application-exp2.conf --run 30s" 2>&1 | tee outputs/exp5-small-pingheavy.log
```

### Results summary

| # | Graph | Algorithm | Sent | Dropped | CONTROL |
|---|-------|-----------|------|---------|---------|
| 1 | Small 21n/20e | None | 513 | 0 | 0 |
| 2 | Small 21n/20e | Wave | 2,746 | 0 | 20  |
| 3 | Medium 51n/54e | Lai-Yang | 9,320 | 476 PING | 54  |
| 4 | Large 101n/138e | Both | 31,993 | 11 WORK | 275  |
| 5 | Large 101n/138e | Both + inject | 8,059 | 4 WORK | 275  |
| 6 | Small 21n/20e | Both, strict | 1,610 | 724 GOSSIP | 40  |

CONTROL always equals (graph edges) × (number of active algorithms) — mathematical proof of correct propagation. Full analysis in `docs/report.md`.

---

## All Run Commands

```bash
# Default run — LargeGraph, 120s, both algorithms (from application.conf)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain"

# Wave only
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s --wave-only"

# Lai-Yang only
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/MediumGraph.ngs --run 60s --snapshot-only"

# Both algorithms (explicit graph + duration)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 120s"

# No algorithms (traffic only)
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 5s --no-algorithms"

# Custom config file
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --config src/main/resources/application-exp2.conf --run 30s"

# File-driven injection
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --inject src/main/resources/inject.txt"

# Interactive injection
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --interactive"
# Then type: node=1 kind=WORK payload=my-task
#            node=2 kind=PING payload=probe
#            quit
```

> **zsh note:** All `sbt` commands must be on a single line. Backslash line continuation inside `sbt "..."` quotes fails in zsh.

### Injection file format (`inject.txt`)

```
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

| Test File | What it tests |
|-----------|--------------|
| `EdgeLabelEnforcementTest` | EdgeLabel allows/blocks correct message types |
| `PdfSamplerTest` | Sampling, reproducibility under seed, Zipf distribution |
| `SimGraphTest` | Graph topology operations (neighbors, hasEdge, counts) |
| `WaveAlgorithmTest` | Wave initiator sends on start, AlgorithmRegistry flags |
| `NodeActorTest` | Actor init, ExternalInput forwarding, edge label blocking, metrics |
| `DistributedNodeSpec` | Prof-provided framework base class tests |
| `LaiYangSnapshotTest` | Snapshot initiator sends CONTROL, non-initiator stays quiet |
| `GraphEnricherSerializerTest` | Enrich assigns labels/PDFs, round-trip JSON serialize/deserialize |
| `FileDrivenInjectorTest` | Parse injection scripts, skip comments, inject correct types |
| `GraphToActorMapperTest` | Creates correct actor count, all ActorRefs distinct and live |

---

## Algorithms

### Wave Algorithm (Index 13)

**Variant:** Echo Algorithm on general directed graph

**Protocol:**
1. Initiator (Node 0) sends WAVE (as CONTROL payload) to all neighbors
2. IDLE nodes receiving WAVE become ACTIVE, forward to all neighbors except parent
3. Leaf nodes immediately ACK parent
4. Nodes collect ACKs from all children; when complete, ACK their own parent
5. Initiator decides when all ACKs received

**Correctness proof:** CONTROL messages in any experiment = graph edges × number of active algorithms. Each directed edge carries exactly one Wave marker. Verified across all experiments.

### Lai-Yang Snapshot Algorithm (Index 23)

**Variant:** Standard Lai-Yang for NON-FIFO channels

**Protocol (RED/WHITE coloring):**
1. Initiator turns RED, records local state, sends SNAPSHOT_MARKER to all neighbors
2. WHITE node receiving first marker: turns RED, records state, forwards markers to all neighbors
3. RED node receiving a WHITE message: captures it as in-flight channel state
4. Snapshot complete per-node when RED and all expected markers received

**Why Lai-Yang over Chandy-Lamport:** Akka's thread-pool dispatcher does not guarantee FIFO message ordering across concurrent actor dispatches. Chandy-Lamport requires FIFO channels. Lai-Yang's coloring handles non-FIFO channels correctly.

---

## Metrics & Observability

Every run prints a final report to stdout. Actual results across all 6 experiments:

```
─────────────────────────────────────────────────────────────────────────────────────
Exp  Graph          Algorithm        Dur   Sent    Drop  CONTROL  PING     GOSSIP   WORK
─────────────────────────────────────────────────────────────────────────────────────
1    Small 21n/20e  None             5s    513     0     0        99       330      84
2    Small 21n/20e  Wave             30s   2,746   0     20     674      1,760    294
3    Medium 51n/54e Lai-Yang         60s   9,320   476   54    838(476↓)7,775    653
4    Large 101n/138e Both            120s  31,993  11    275   2,613    27,740   1,365(11↓)
5    Large 101n/138e Both + inject   30s   8,059   4     275    663      6,822    299(4↓)
6    Small 21n/20e  Both, strict     30s   1,610   725   40    0        0(724↓) 1,570
─────────────────────────────────────────────────────────────────────────────────────
```

**Key invariant — CONTROL = edges × algorithms:**

| Experiment | Graph edges | Algorithms | CONTROL | Verified |
|-----------|-------------|------------|---------|----------|
| Exp 2 | 20 | Wave (×1) | 20 |  |
| Exp 3 | 54 | Lai-Yang (×1) | 54 |  |
| Exp 4 | 138 | Both (×2) | 275 |  |
| Exp 5 | 138 | Both (×2) | 275 |  |
| Exp 6 | 20 | Both (×2) | 40 |  |

CONTROL always equals (graph edges) × (number of active algorithms) — mathematical proof that both Wave and Lai-Yang propagated through every edge in the topology.

**Drop breakdown:**
- Exp 3 — 476 PING drops: edge `35→9` blocks PING; Node 35's only outgoing edge, 20% PING in its PDF, 60 seconds
- Exp 4 — 11 WORK drops: WORK cascade coin flip says "forward" but no WORK-eligible neighbor exists
- Exp 5 — 4 WORK drops: same LargeGraph topology as Exp 4, shorter run
- Exp 6 — 724 GOSSIP drops: strict config blocks GOSSIP on all of Node 0's outgoing edges; 0 GOSSIP transmitted

All counters use `TrieMap` + `AtomicLong` for lock-free concurrent updates across actor threads. Metrics collected via:
- `MetricsCollector.recordSent(from, to, kind)` — in `sendToEligibleNeighbor`
- `MetricsCollector.recordDropped(from, -1, kind)` — when no eligible neighbor found
- `MetricsCollector.recordReceived(nodeId, kind)` — on every received `Envelope`

---

## Cinnamon Instrumentation

The Lightbend Cinnamon sbt plugin (v2.21.4) is configured in `project/plugins.sbt`, build.sbt.

---

## Versions

| Component | Version |
|-----------|---------|
| Scala | 3.3.1 |
| Akka Classic | 2.8.5 |
| sbt | 1.9.7 |
| Java | 17 (required) |
| ScalaTest | 3.2.17 |
| Logback | 1.4.11 |
| Circe (JSON) | 0.14.6 |
| Typesafe Config | 1.4.2 |
| Cinnamon plugin | 2.21.4 |
| NetGameSim | Submodule @ 0x1DOCD00D/NetGameSim |
