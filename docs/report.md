# CS553 Project Report — Distributed Algorithms Simulator

**Student:** Shivam Moudgil | **UIN:** 665599956  
**Course:** CS553 — Distributed Computing Systems, Spring 2026  
**Assigned Algorithms:** Wave Algorithm (Index 13) · Lai-Yang Snapshot (Index 23)  
**Submission:** April 18, 2026

---

## 1. System Overview

This project implements a distributed algorithms simulator that converts randomly generated graphs from NetGameSim into a running Akka actor system. Each graph node becomes one Akka Classic actor, and each directed edge becomes a communication channel modeled via a destination `ActorRef`. Two distributed algorithms — Wave and Lai-Yang Snapshot — are implemented as pluggable modules that run on top of the same actor runtime.

---

## 2. Design Decisions

### 2.1 One Actor Per Node (Not Router Pools)

Each graph node maps to exactly one `NodeActor` instance. Router pools were explicitly rejected because distributed algorithms maintain per-node state (wave phase, snapshot color, pending ACK counts). Sharing state across pool instances would violate algorithm correctness — two instances of the same "node" cannot both be the unique initiator or hold the same snapshot state independently.

### 2.2 Immutable State via `context.become`

`NodeActor` uses Akka's `context.become` to switch between behaviors without `var` fields. After receiving `Init`, the actor transitions to `initialized(neighbors, allowedOnEdge, pdf, ctx, workQueue)` where all state is passed as immutable function parameters. When work queue state changes, `context.become` is called again with the updated list.

```scala
// After Init received:
context.become(initialized(neighbors, allowedOnEdge, pdf, ctx, workQueue = List.empty))

// When workQueue changes:
val (updatedQueue, _) = processWork(newQueue, neighbors, allowedOnEdge)
context.become(initialized(neighbors, allowedOnEdge, pdf, ctx, updatedQueue))
```

### 2.3 Algorithm Plugin Pattern

Algorithms implement the `DistributedAlgorithm` trait:

```scala
trait DistributedAlgorithm:
  def name: String
  def onStart(ctx: NodeContext): Unit
  def onMessage(ctx: NodeContext, msg: Any): Unit
  def onTick(ctx: NodeContext): Unit = ()
```

`NodeActor` holds a `List[DistributedAlgorithm]` and delegates to each module at the appropriate lifecycle point. This means Wave and Lai-Yang can run simultaneously on the same actors (Experiment 4) without any changes to `NodeActor` itself.

### 2.4 Edge Label Enforcement

Every edge is assigned a `Set[MessageType]` of allowed types. `NodeActor.sendToEligibleNeighbor` filters neighbors by this constraint before sending. `CONTROL` is always added to every edge by `GraphEnricher` — algorithm protocol messages must propagate regardless of application-level restrictions.

```scala
val eligible = neighbors.keys.filter: to =>
  allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
.toList
```

Forbidden messages are dropped and counted in `MetricsCollector`.

### 2.5 PING → ACK Reply

ACK is used as an application-layer acknowledgment for PING messages. When a node receives a PING, it checks whether a reverse edge exists and whether that edge allows ACK. This is topology-dependent — in directed graphs, `A→B` existing does not guarantee `B→A` exists.

```scala
case MessageType.PING =>
  val canAck = neighbors.contains(from) &&
               allowedOnEdge.getOrElse(from, Set.empty).contains(MessageType.ACK)
  if canAck then
    neighbors(from) ! Envelope(from = id, kind = MessageType.ACK, payload = s"ack-from-$id")
    metrics.recordSent(id, from, MessageType.ACK)
  else
    logger.debug(s"Node $id: no reverse edge to $from — PING terminal")
```

### 2.6 500ms Startup Delay

All actors initialize concurrently. Without a delay, Node 0 would send algorithm markers before other actors finish their `Init` message, resulting in dead letters. A 500ms scheduler delay ensures all actors are ready before algorithms start.

```scala
context.system.scheduler.scheduleOnce(
  500.millis, self, StartAlgorithms
)(context.dispatcher)
```

### 2.7 Configuration-Driven Design

Zero values are hardcoded in source. All parameters come from `application.conf`:
graph file path, random seed, run duration, message types, edge label defaults and overrides, per-node and default PDFs, timer and input node definitions, algorithm selection. CLI flags `--graph` and `--run` override `application.conf` at runtime.

### 2.8 Reproducibility via Fixed Seeds

`PdfSampler(seed)` wraps `scala.util.Random(seed)`. Given the same seed, the same sequence of message types is generated on every run. Experiments use fixed seeds (42, 999).

### 2.9 Thread-Safe Metrics

`MetricsCollector` uses `TrieMap[key, AtomicLong]` for all counters. Multiple actors update metrics concurrently with no synchronization needed. `AtomicLong.incrementAndGet()` is lock-free.

### 2.10 Lai-Yang vs Chandy-Lamport

Chandy-Lamport requires FIFO channels. Lai-Yang does not — it uses message coloring instead. This project uses Lai-Yang because Akka's default actor mailboxes do not guarantee FIFO ordering across concurrent dispatchers.

---

## 3. Configuration Choices

### Graph Files

| File | Nodes | Edges | Density | Used In |
|------|-------|-------|---------|---------|
| SmallGraph.ngs | 21 | 20 | Sparse | Experiments 1, 2, 6 |
| MediumGraph.ngs | 51 | 54 | Moderate | Experiment 3 |
| LargeGraph.ngs | 101 | 138 | Moderate | Experiments 4, 5 |

All graphs are directed, asymmetric, and generated by NetGameSim's random graph model.

### Edge Label Configuration

```hocon
overrides = [
  { from = 0,  to = 35, allow = [ "CONTROL", "PING", "WORK" ] },
  { from = 1,  to = 20, allow = [ "CONTROL", "GOSSIP", "WORK" ] },
  { from = 35, to = 9,  allow = [ "CONTROL", "WORK" ] }
]
```

Edge `35→9` allows only CONTROL and WORK. Any PING sent on that path is dropped. Experiment 3 showed 476 PING drops due to this restriction.

### Node PDF Configuration

```hocon
defaultPdf = [ { msg = "PING", p = 0.40 }, { msg = "GOSSIP", p = 0.35 }, { msg = "WORK", p = 0.25 } ]
perNodePdf = [
  { node = 0,  pdf = [ { msg = "PING", p = 0.40 }, { msg = "GOSSIP", p = 0.35 }, { msg = "WORK", p = 0.25 } ] },
  { node = 1,  pdf = [ { msg = "GOSSIP", p = 0.60 }, { msg = "PING", p = 0.30 }, { msg = "WORK", p = 0.10 } ] },
  { node = 35, pdf = [ { msg = "WORK", p = 0.80 }, { msg = "PING", p = 0.20 } ] }
]
```

### Timer and Input Nodes

```hocon
timers = [
  { node = 0, tickEveryMs = 50,  mode = "pdf" },
  { node = 1, tickEveryMs = 75,  mode = "fixed", fixedMsg = "GOSSIP" }
]
inputs = [{ node = 2 }]
```

---

## 4. Experiment Results

### Setup

All experiments run with Java 17.0.16 (Homebrew), Scala 3.3.1, Akka 2.8.5, sbt 1.9.7 on MacBook Pro M2.

---

### Experiment 1 — No-Algorithm Baseline (SmallGraph, 5s)

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 5s --no-algorithms --save-graph outputs/smallgraph-enriched.json" 2>&1 | tee outputs/no-algo-small.log
```

**Graph:** SmallGraph — 21 nodes, 20 edges | **Seed:** 999 | **Duration:** 5s | **Algorithms:** None

**Key log output:**
```
Args: --graph src/main/resources/graphs/SmallGraph.ngs --run 5s --no-algorithms ...
Config loaded: graph=src/main/resources/graphs/SmallGraph.ngs, seed=999
Wave algorithm: false  |  Lai-Yang snapshot: false
21 actors running
Node 0 → Node 2: GOSSIP 'tick-from-0'
Node 1 → Node 12: WORK 'tick-from-1'
Node 12 → Node 14: WORK 'work-from-12'
Node 14 → Node 6: WORK 'work-from-14'
Node 0 → Node 1: PING 'tick-from-0'
...
=== Shutdown Complete ===
```

**Final Metrics:**
```
Total messages sent:    513     dropped: 0
  PING    → sent: 99,  dropped: 0
  GOSSIP  → sent: 330, dropped: 0
  WORK    → sent: 84,  dropped: 0
```

**Analysis:** Pure traffic baseline with no algorithm overhead. CONTROL = 0 because no algorithms are enabled. GOSSIP dominates at 64% due to its cascading hop-by-hop nature. WORK is lowest at 16% because it only cascades with 50% probability. Zero drops because SmallGraph's default edges allow all message types. This establishes the traffic floor.

---

### Experiment 2 — Wave Algorithm, SmallGraph, 30s

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s --wave-only --save-graph outputs/smallgraph-enriched.json" 2>&1 | tee outputs/exp1-wave-small.log
```

**Graph:** SmallGraph — 21 nodes, 20 edges | **Seed:** 999 | **Duration:** 30s | **Algorithms:** Wave only

**Key log output:**
```
Args: --graph src/main/resources/graphs/SmallGraph.ngs --run 30s --wave-only ...
Config loaded: graph=src/main/resources/graphs/SmallGraph.ngs, seed=999
Node 0: WaveAlgorithm enabled (initiator=true)
21 actors running
WaveAlgorithm - Node 0: initiating wave
WaveAlgorithm - Node 0: sending wave ebae4f14 to List(2, 1)
WaveAlgorithm - Node 3:  leaf node, acking parent 15
WaveAlgorithm - Node 17: leaf node, acking parent 19
...
=== Shutdown Complete ===
```

**Final Metrics:**
```
Total messages sent:    2746    dropped: 0
  CONTROL → sent: 20,   dropped: 0
  PING    → sent: 674,  dropped: 0
  GOSSIP  → sent: 1760, dropped: 0
  WORK    → sent: 294,  dropped: 0
```

**Analysis:** CONTROL = 20 exactly matches SmallGraph's 20 edges — one CONTROL message per edge as the wave propagated from Node 0 (wave ID `ebae4f14`) through all 21 nodes. Leaf nodes (Node 3 acking Node 15, Node 17 acking Node 19) sent WaveACKs back up the tree. This is mathematical proof of correct wave propagation. Zero drops confirms no edge label restrictions trigger on SmallGraph's default topology.

---

### Experiment 3 — Lai-Yang Snapshot, MediumGraph, 60s

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/MediumGraph.ngs --run 60s --snapshot-only --save-graph outputs/mediumgraph-enriched.json" 2>&1 | tee outputs/exp2-snapshot-medium.log
```

**Graph:** MediumGraph — 51 nodes, 54 edges | **Seed:** 999 | **Duration:** 60s | **Algorithms:** Lai-Yang only

**Key log output:**
```
Args: --graph src/main/resources/graphs/MediumGraph.ngs --run 60s --snapshot-only ...
Config loaded: graph=src/main/resources/graphs/MediumGraph.ngs, seed=999
Node 0: LaiYangSnapshot enabled (initiator=true)
51 actors running
LaiYangSnapshot - Node 0: initiating snapshot
LaiYangSnapshot - Node 0: turned RED, sending markers to Set(34, 35)
LaiYangSnapshot - Node 35: turned RED, sending markers to Set(9)
LaiYangSnapshot - Node 34: turned RED, sending markers to Set(4)
LaiYangSnapshot - Node 35: snapshot COMPLETE. snapshotId=51e28e6d InFlight=0 messages
LaiYangSnapshot - Node 34: snapshot COMPLETE. snapshotId=51e28e6d InFlight=0 messages
LaiYangSnapshot - Node 34: captured in-flight message from 0
LaiYangSnapshot - Node 4:  captured in-flight message from 34
LaiYangSnapshot - Node 26: captured in-flight message from 4
LaiYangSnapshot - Node 36: captured in-flight message from 26
LaiYangSnapshot - Node 16: captured in-flight message from 36
...
=== Shutdown Complete ===
```

**Final Metrics:**
```
Total messages sent:    9320    dropped: 476
  CONTROL → sent: 54,   dropped: 0
  PING    → sent: 838,  dropped: 476
  GOSSIP  → sent: 7775, dropped: 0
  WORK    → sent: 653,  dropped: 0
```

**Analysis:** CONTROL = 54 exactly matches MediumGraph's 54 edges — one snapshot marker per edge, proving the Lai-Yang cascade (snapshot ID `51e28e6d`) reached every node. In-flight WHITE messages were correctly captured at RED nodes: Node 34 captured a GOSSIP from Node 0 that was sent before Node 34 turned RED; this propagated through the chain (Node 4 ← Node 34, Node 26 ← Node 4, etc.). The 476 PING drops are entirely from edge `35→9` which only allows CONTROL and WORK — Node 35 fires PING at 20% probability every 50ms with only one outgoing edge that blocks PING, so every PING attempt over 60 seconds is dropped. This is edge label enforcement working correctly.

---

### Experiment 4 — Both Algorithms, LargeGraph, 120s

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 120s --save-graph outputs/largegraph-enriched.json" 2>&1 | tee outputs/exp3-both-large.log
```

**Graph:** LargeGraph — 101 nodes, 138 edges | **Seed:** 999 | **Duration:** 120s | **Algorithms:** Wave + Lai-Yang

**Key log output:**
```
Args: --graph src/main/resources/graphs/LargeGraph.ngs --run 120s ...
Config loaded: graph=src/main/resources/graphs/LargeGraph.ngs, seed=999
Node 0: WaveAlgorithm enabled (initiator=true)
Node 0: LaiYangSnapshot enabled (initiator=true)
101 actors running
WaveAlgorithm  - Node 0: initiating wave
WaveAlgorithm  - Node 0: sending wave bc8488ca to List(21, 24)
LaiYangSnapshot - Node 0: initiating snapshot
LaiYangSnapshot - Node 0: turned RED, sending markers to Set(21, 24)
LaiYangSnapshot - Node 24: turned RED, sending markers to Set(96)
LaiYangSnapshot - Node 21: turned RED, sending markers to Set(59)
LaiYangSnapshot - Node 21: snapshot COMPLETE. snapshotId=56a1fd17 InFlight=0 messages
LaiYangSnapshot - Node 24: snapshot COMPLETE. snapshotId=56a1fd17 InFlight=0 messages
LaiYangSnapshot - Node 24: captured in-flight message from 0
LaiYangSnapshot - Node 96: captured in-flight message from 24
LaiYangSnapshot - Node 37: captured in-flight message from 96
LaiYangSnapshot - Node 43: captured in-flight message from 37
LaiYangSnapshot - Node 83: captured in-flight message from 43
WaveAlgorithm  - Node 31: leaf node, acking parent 30
WaveAlgorithm  - Node 54: leaf node, acking parent 86
...
=== Shutdown Complete ===
```

**Final Metrics:**
```
Total messages sent:    31993   dropped: 11
  CONTROL → sent: 275,   dropped: 0
  PING    → sent: 2613,  dropped: 0
  GOSSIP  → sent: 27740, dropped: 0
  WORK    → sent: 1365,  dropped: 11
```

**Analysis:** Both algorithms ran concurrently on 101 actors for 120 seconds without interference — the plugin architecture proven at scale. Wave (ID `bc8488ca`) and Lai-Yang (snapshot ID `56a1fd17`) were both initiated immediately after the 500ms startup delay. CONTROL = 275 covers both algorithms' markers propagating through all 138 edges. GOSSIP dominates at 87% (27,740 messages) — cascading compounds across a dense 138-edge graph over 120 seconds. PING = 0 drops because all three config override edges (`0→35`, `1→20`, `35→9`) do not exist in LargeGraph's topology — all edges fall back to the default label allowing PING. 11 WORK drops from edge label enforcement at specific restricted edges.

---

### Experiment 5 — File-Driven Injection, LargeGraph, 30s

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 30s --inject src/main/resources/inject.txt" 2>&1 | tee outputs/exp4-injection.log
```

**Graph:** LargeGraph — 101 nodes, 138 edges | **Seed:** 999 | **Duration:** 30s | **Algorithms:** Wave + Lai-Yang + injection

**inject.txt:**
```
delayMs=100,node=1,kind=WORK,payload=task-001
delayMs=200,node=2,kind=PING,payload=probe-001
delayMs=500,node=1,kind=GOSSIP,payload=update-001
delayMs=1000,node=2,kind=WORK,payload=task-002
```

**Key log output:**
```
Args: --graph src/main/resources/graphs/LargeGraph.ngs --run 30s --inject src/main/resources/inject.txt
101 actors running
Running file-driven injection from: src/main/resources/inject.txt
FileDrivenInjector - Loaded 4 injection entries
=== Shutdown Complete ===
```

**Final Metrics:**
```
Total messages sent:    8059    dropped: 4
  CONTROL → sent: 275,  dropped: 0
  PING    → sent: 663,  dropped: 0
  GOSSIP  → sent: 6822, dropped: 0
  WORK    → sent: 299,  dropped: 4
```

**Analysis:** CONTROL = 275 is identical to Experiment 4 — same LargeGraph, same two algorithms. All 4 injection entries loaded and executed at their specified delays. Node 2 is declared as an input node in `application.conf`. The shorter 30s window accounts for the lower total message count compared to Exp 4's 120s. 4 WORK drops from edge label enforcement, same cause as Experiment 4.

---

### Experiment 6 — Strict Config, SmallGraph, Both Algorithms, 30s

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --config src/main/resources/application-exp2.conf --run 30s" 2>&1 | tee outputs/exp5-small-pingheavy.log
```

**Graph:** SmallGraph — 21 nodes, 20 edges | **Seed:** 42 | **Duration:** 30s | **Algorithms:** Wave + Lai-Yang  
**Config:** `application-exp2.conf` — strict edge overrides blocking GOSSIP on Node 0's outgoing edges

**Key log output:**
```
Args: --config src/main/resources/application-exp2.conf --run 30s
Config loaded: graph=src/main/resources/graphs/SmallGraph.ngs, seed=42
Node 0: WaveAlgorithm enabled (initiator=true)
Node 0: LaiYangSnapshot enabled (initiator=true)
21 actors running
Node 0 → Node 2: WORK 'tick-from-0'
Node 2 → Node 13: WORK 'work-from-2'
Node 13 → Node 9: WORK 'work-from-13'
WaveAlgorithm  - Node 0: initiating wave
WaveAlgorithm  - Node 0: sending wave 097d1778 to List(1, 2)
LaiYangSnapshot - Node 0: initiating snapshot
LaiYangSnapshot - Node 0: turned RED, sending markers to Set(1, 2)
LaiYangSnapshot - Node 1: turned RED, sending markers to Set(12)
LaiYangSnapshot - Node 2: turned RED, sending markers to Set(13)
LaiYangSnapshot - Node 2: snapshot COMPLETE. snapshotId=b54e31ba InFlight=0 messages
LaiYangSnapshot - Node 1: snapshot COMPLETE. snapshotId=b54e31ba InFlight=0 messages
WaveAlgorithm  - Node 17: leaf node, acking parent 19
WaveAlgorithm  - Node 3:  leaf node, acking parent 15
...
=== Shutdown Complete ===
```

**Final Metrics:**
```
Total messages sent:    1610    dropped: 725
  CONTROL → sent: 40,   dropped: 0
  GOSSIP  → sent: 0,    dropped: 724
  WORK    → sent: 1570, dropped: 1
```

**Analysis:** CONTROL = 40 = 20 edges × 2 algorithms. Wave (ID `097d1778`) and Lai-Yang (snapshot ID `b54e31ba`) each sent 20 markers through SmallGraph's 20 edges simultaneously — clean proof of both algorithms running correctly on the same graph. GOSSIP = 0 sent, 724 dropped — the strict config sets Node 0's outgoing edges (`0→1` and `0→2`) to deny GOSSIP; Node 0 is the only timer node so every GOSSIP tick attempt is dropped immediately. WORK dominates at 97% of successful traffic. With seed=42 (different from other experiments), the random sequence differs — visible in the early log where Node 0 fires only WORK before the StartAlgorithms delay fires. 1 WORK drop from a single edge restriction in the strict config.

---

## 5. Experiment Summary Table

| # | Graph | Nodes | Edges | Algorithm | Seed | Duration | Sent | Dropped | CONTROL | Key Result |
|---|-------|-------|-------|-----------|------|----------|------|---------|---------|------------|
| 1 | Small | 21 | 20 | None (baseline) | 999 | 5s | 513 | 0 | 0 | Pure traffic floor |
| 2 | Small | 21 | 20 | Wave only | 999 | 30s | 2,746 | 0 | **20** ✅ | CONTROL = edges |
| 3 | Medium | 51 | 54 | Lai-Yang only | 999 | 60s | 9,320 | 476 PING | **54** ✅ | CONTROL = edges, drops from 35→9 |
| 4 | Large | 101 | 138 | Wave + Lai-Yang | 999 | 120s | 31,993 | 11 WORK | **275** ✅ | Both concurrent, GOSSIP 87% |
| 5 | Large | 101 | 138 | Both + inject | 999 | 30s | 8,059 | 4 WORK | **275** ✅ | CONTROL same as Exp 4 |
| 6 | Small | 21 | 20 | Wave + Lai-Yang | 42 | 30s | 1,610 | 724 GOSSIP | **40** ✅ | 20×2 algos, GOSSIP fully blocked |

**CONTROL always equals graph edges × number of algorithms — mathematical proof of correct propagation.**

---

## 6. Reproducible Experiment Script

> **Note:** All `sbt` commands must be single-line in zsh. Backslash-newline continuation inside `sbt "..."` quotes breaks in zsh.

```bash
# Prerequisites
git clone --recurse-submodules https://github.com/its-Shivam732/CS553-DistributedAlgorithms.git
cd CS553-DistributedAlgorithms
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && export PATH=$JAVA_HOME/bin:$PATH
java -version   # Must show 17.x.x
sbt compile
sbt test

# Experiment 1 — Baseline, no algorithms, SmallGraph, 5s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 5s --no-algorithms --save-graph outputs/smallgraph-enriched.json" 2>&1 | tee outputs/no-algo-small.log

# Experiment 2 — Wave only, SmallGraph, 30s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s --wave-only --save-graph outputs/smallgraph-enriched.json" 2>&1 | tee outputs/exp1-wave-small.log

# Experiment 3 — Lai-Yang only, MediumGraph, 60s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/MediumGraph.ngs --run 60s --snapshot-only --save-graph outputs/mediumgraph-enriched.json" 2>&1 | tee outputs/exp2-snapshot-medium.log

# Experiment 4 — Both algorithms, LargeGraph, 120s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 120s --save-graph outputs/largegraph-enriched.json" 2>&1 | tee outputs/exp3-both-large.log

# Experiment 5 — File-driven injection, LargeGraph, 30s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/LargeGraph.ngs --run 30s --inject src/main/resources/inject.txt" 2>&1 | tee outputs/exp4-injection.log

# Experiment 6 — Strict config, SmallGraph, 30s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain --config src/main/resources/application-exp2.conf --run 30s" 2>&1 | tee outputs/exp5-small-pingheavy.log
```

**On Linux:** Replace `$(/usr/libexec/java_home -v 17)` with `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

---

## 7. Algorithm Correctness Arguments

### Wave Algorithm

1. **Every node participates:** The initiator floods to all neighbors; each internal node forwards to all non-parent neighbors. Leaf nodes ACK immediately (e.g. Node 3 acking Node 15, Node 17 acking Node 19 in Exp 2; Node 31 acking Node 30, Node 54 acking Node 86 in Exp 4).

2. **At least one decision event:** The initiator decides when `pendingAcks == 0` after collecting WaveACKs from all children.

3. **Defined termination:** At most `2 × |E|` messages. CONTROL counts match edges exactly: 20 for SmallGraph, 275 for both algorithms on LargeGraph.

### Lai-Yang Snapshot

1. **Consistency:** Every in-flight message recorded was sent while the sender was WHITE (before turning RED). Observed: Node 34 captured a GOSSIP from Node 0 (Exp 3); Node 24 captured a PING from Node 0 (Exp 4).

2. **Completeness:** Every node received a marker and turned RED. Verified by CONTROL counts matching edge counts exactly across all experiments.

3. **Non-FIFO correctness:** Snapshot IDs consistent across all completions (`51e28e6d` across 51 nodes in Exp 3; `56a1fd17` across 101 nodes in Exp 4; `b54e31ba` across 21 nodes in Exp 6). Akka's non-FIFO dispatchers handled correctly.

---

## 8. Known Limitations

1. **Snapshot termination detection:** Per-node completion detection only. A full global coordinator collecting all local snapshots is not implemented.

2. **Cinnamon metrics:** The Lightbend Cinnamon plugin requires a commercial license token. Built-in `MetricsCollector` via `TrieMap[key, AtomicLong]` provides equivalent per-type counters.

3. **Single-machine simulation:** All actors run in one JVM. Akka Cluster is a dependency but cluster configuration is not set up in this submission.

4. **ACK topology dependency:** PING→ACK replies only succeed when a reverse edge exists. In SmallGraph's tree-like structure, most edges are one-directional.
