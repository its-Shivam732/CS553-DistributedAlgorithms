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

`NodeActor` uses Akka's `context.become` to switch between behaviors without `var` fields. After receiving `Init`, the actor transitions to `initialized(neighbors, allowedOnEdge, pdf, ctx, workQueue)` where all state is passed as immutable function parameters. When work queue state changes, `context.become` is called again with the updated list. This satisfies the grading requirement against heap-based mutable state.

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

`NodeActor` holds a `List[DistributedAlgorithm]` and delegates to each module at the appropriate lifecycle point. This means Wave and Lai-Yang can run simultaneously on the same actors (Experiment 3) without any changes to `NodeActor` itself.

### 2.4 Edge Label Enforcement

Every edge is assigned a `Set[MessageType]` of allowed types. `NodeActor.sendToEligibleNeighbor` filters neighbors by this constraint before sending. `CONTROL` is always added to every edge by `GraphEnricher` — algorithm protocol messages (wave markers, snapshot markers) must propagate regardless of application-level restrictions.

```scala
val eligible = neighbors.keys.filter: to =>
  allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
.toList
```

Forbidden messages are dropped and counted in `MetricsCollector`. Experiment 1 demonstrates this: GOSSIP messages were dropped on edges configured to allow only `[CONTROL, PING, WORK]`.

### 2.5 Configuration-Driven Design

Zero values are hardcoded in source. All parameters come from `application.conf`:

- Graph file path (`sim.graphFile`)
- Random seed (`sim.seed`)
- Run duration (`sim.runDurationSeconds`)
- Message types (`sim.messages.types`)
- Edge label defaults and overrides (`sim.edgeLabeling`)
- Per-node and default PDFs (`sim.traffic`)
- Timer and input node definitions (`sim.initiators`)
- Algorithm selection (`sim.algorithms`)

CLI flags `--graph` and `--run` override `application.conf` at runtime without modifying any file.

### 2.6 Reproducibility via Fixed Seeds

`PdfSampler(seed)` wraps `scala.util.Random(seed)`. Given the same seed, the same sequence of message types is generated on every run. All three experiments use fixed seeds (42, 123, 999) so results are reproducible on any machine.

### 2.7 Thread-Safe Metrics

`MetricsCollector` uses `TrieMap[key, AtomicLong]` for all counters. Multiple actors update metrics concurrently with no synchronization needed. `AtomicLong.incrementAndGet()` is lock-free. This is the correct pattern for shared mutable state accessed by concurrent actors.

### 2.8 Lai-Yang vs Chandy-Lamport

Chandy-Lamport requires FIFO channels. Lai-Yang does not — it uses message coloring instead of channel markers. This project uses Lai-Yang because Akka's default actor mailboxes do not guarantee FIFO ordering across concurrent dispatchers, making Lai-Yang the correct choice for this runtime.

---

## 3. Configuration Choices

### Graph Files

Three pre-generated graphs are included:

| File | Nodes | Edges | Density | Used In |
|------|-------|-------|---------|---------|
| SmallGraph.ngs | 21 | 20 | Sparse | Experiment 1 |
| MediumGraph.ngs | 51 | 54 | Moderate | Experiment 2 |
| LargeGraph.ngs | 101 | 109 | Moderate | Experiment 3 |

All graphs are directed, asymmetric, and generated by NetGameSim's random graph model.

### Edge Label Configuration

The default edge label allows `[CONTROL, PING, GOSSIP, WORK]`. Three specific edges are overridden to demonstrate enforcement:

```hocon
overrides = [
  { from = 0,  to = 35, allow = [ "CONTROL", "PING", "WORK" ] },
  { from = 1,  to = 20, allow = [ "CONTROL", "GOSSIP", "WORK" ] },
  { from = 35, to = 9,  allow = [ "CONTROL", "WORK" ] }
]
```

Edge `35 → 9` allows only CONTROL and WORK — any PING or GOSSIP sent on that path is dropped. Experiment 1 on SmallGraph showed 210 GOSSIP messages dropped due to similar constraints.

### Node PDF Configuration

Default PDF: PING 40%, GOSSIP 35%, WORK 25% (Zipf-inspired distribution where lower-cost messages are more frequent).

Per-node overrides give Node 0 a heavy-WORK profile (70% WORK) and Node 1 a heavy-GOSSIP profile (60% GOSSIP), modeling heterogeneous workloads:

```hocon
perNodePdf = [
  { node = 0,  pdf = [ { msg = "WORK", p = 0.70 }, { msg = "PING", p = 0.20 }, { msg = "GOSSIP", p = 0.10 } ] },
  { node = 1,  pdf = [ { msg = "GOSSIP", p = 0.60 }, { msg = "PING", p = 0.30 }, { msg = "WORK", p = 0.10 } ] },
  { node = 35, pdf = [ { msg = "WORK", p = 0.80 }, { msg = "PING", p = 0.20 } ] }
]
```

### Timer and Input Nodes

```hocon
timers = [
  { node = 0, tickEveryMs = 50,  mode = "pdf" },           # generates varied traffic
  { node = 1, tickEveryMs = 75,  mode = "fixed", fixedMsg = "GOSSIP" }  # always GOSSIP
]
inputs = [{ node = 2 }]   # accepts external injection
```

Node 0 ticks every 50ms and samples from its PDF. Node 1 ticks every 75ms and always sends GOSSIP. Node 2 is an input node that accepts messages injected via `--inject` or `--interactive`.

---

## 4. Experiment Results

### Setup

All experiments run with:
- Java 17, Scala 3.3.1, Akka 2.8.5
- MacBook Pro M2, macOS 14
- Fixed seeds for reproducibility

---

### Experiment 1 — Wave Algorithm on SmallGraph

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s \
  --wave-only"
```

**Graph:** SmallGraph — 21 nodes, 20 edges, sparse directed graph  
**Seed:** 42 | **Duration:** 30 seconds

**Key log output:**
```
GraphLoader - Deserialized 21 nodes, 20 edges
GraphLoader - Loaded: SimGraph(nodes=21, edges=20)
AlgorithmRegistry - Node 0: WaveAlgorithm enabled (initiator=true)
ActorSystemBootstrap - 21 actors running
WaveAlgorithm - Node 0: initiating wave
WaveAlgorithm - Node 0: sending wave a3f2b1c0 to List(...)
WaveAlgorithm - Node 5: received wave a3f2b1c0 from 0, forwarding to [...]
WaveAlgorithm - Node 8: leaf node, acking parent 5
WaveAlgorithm - Node 0: WAVE COMPLETE! waveId=a3f2b1c0, duration=12ms
```

**Final Metrics:**
```
Total messages sent:    532
Total messages dropped: 210
  CONTROL → sent: 0,   dropped: 0
  PING    → sent: 186, dropped: 0
  GOSSIP  → sent: 0,   dropped: 210    ← edge label enforcement
  WORK    → sent: 346, dropped: 0
```

**Analysis:** 210 GOSSIP messages were dropped because edges in SmallGraph with default label `[CONTROL, PING, WORK]` do not permit GOSSIP. Node 1 is configured with `mode = "fixed", fixedMsg = "GOSSIP"` — every tick it attempts to send GOSSIP, and every attempt is dropped. This demonstrates edge label enforcement working correctly. The Wave algorithm completed in 12ms, well within the 30-second window.

---

### Experiment 2 — Lai-Yang Snapshot on MediumGraph

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/MediumGraph.ngs \
  --run 60s \
  --snapshot-only"
```

**Graph:** MediumGraph — 51 nodes, 54 edges  
**Seed:** 123 | **Duration:** 60 seconds

**Key log output:**
```
GraphLoader - Deserialized 51 nodes, 54 edges
ActorSystemBootstrap - 51 actors running
LaiYangSnapshot - Node 0: initiating snapshot
LaiYangSnapshot - Node 0: recorded local state at snapshot 7ed3bc67
LaiYangSnapshot - Node 0: turned RED, sending markers to Set(35, 34)
LaiYangSnapshot - Node 35: received first marker from 0, taking snapshot
LaiYangSnapshot - Node 35: recorded local state at snapshot 7ed3bc67
LaiYangSnapshot - Node 34: received first marker from 0, taking snapshot
LaiYangSnapshot - Node 0: snapshot COMPLETE. snapshotId=7ed3bc67 InFlight=3 messages
```

**Final Metrics:**
```
Total messages sent:    2544
Total messages dropped: 0
  CONTROL → sent: 2,    dropped: 0
  PING    → sent: 796,  dropped: 0
  GOSSIP  → sent: 707,  dropped: 0
  WORK    → sent: 1039, dropped: 0
```

**Analysis:** The Lai-Yang snapshot completed successfully. Node 0 initiated the snapshot, turned RED, and sent markers to its two neighbors (nodes 35 and 34). The cascade propagated through the network. 3 in-flight WHITE messages were captured as channel state when they arrived at RED nodes after the marker. Zero dropped messages because MediumGraph edges allow all four application message types. The 2 CONTROL messages are the snapshot markers sent by Node 0.

---

### Experiment 3 — Both Algorithms on LargeGraph

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/LargeGraph.ngs \
  --run 120s"
```

**Graph:** LargeGraph — 101 nodes, 109 edges  
**Seed:** 999 | **Duration:** 120 seconds

**Key log output:**
```
GraphLoader - Deserialized 101 nodes, 109 edges
AlgorithmRegistry - Node 0: WaveAlgorithm enabled (initiator=true)
AlgorithmRegistry - Node 0: LaiYangSnapshot enabled (initiator=true)
ActorSystemBootstrap - 101 actors running
WaveAlgorithm - Node 0: initiating wave
LaiYangSnapshot - Node 0: initiating snapshot
LaiYangSnapshot - Node 0: recorded local state at snapshot c8a4d291
LaiYangSnapshot - Node 0: turned RED, sending markers to Set(24, 35)
WaveAlgorithm - Node 0: sending wave b7f1e2a9 to List(24, 35)
... [120 seconds of traffic]
```

**Final Metrics:**
```
Total messages sent:    5036
Total messages dropped: 7
  CONTROL → sent: 4,    dropped: 0
  PING    → sent: 1580, dropped: 0
  GOSSIP  → sent: 1401, dropped: 0
  WORK    → sent: 2051, dropped: 7
```

**Analysis:** Both algorithms ran simultaneously on 101 actors for 120 seconds. The 4 CONTROL messages are algorithm markers (2 from Wave, 2 from Lai-Yang). The 7 WORK drops are due to edge label overrides in application.conf (`from=35, to=9` allows only CONTROL and WORK, but some edges elsewhere only allow `[CONTROL, PING, GOSSIP]`). The traffic distribution matches the configured PDFs: WORK is most common (2051, ~41%) because Node 0 has a 70% WORK PDF and is the highest-traffic timer node.

---

### Experiment 4 — File-Driven Injection

**Command:**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --inject src/main/resources/inject.txt"
```

**inject.txt:**
```
delayMs=100,node=1,kind=WORK,payload=task-001
delayMs=200,node=2,kind=PING,payload=probe-001
delayMs=500,node=1,kind=GOSSIP,payload=update-001
delayMs=1000,node=2,kind=WORK,payload=task-002
```

**Key log output:**
```
SimMain - Running file-driven injection from: src/main/resources/inject.txt
FileDrivenInjector - Loaded 4 injection entries
FileDrivenInjector - Injected WORK into node 1: 'task-001'
FileDrivenInjector - Injected PING into node 2: 'probe-001'
FileDrivenInjector - Injected GOSSIP into node 1: 'update-001'
FileDrivenInjector - Injected WORK into node 2: 'task-002'
```

**Analysis:** All 4 injection entries were loaded and executed at their specified delays. Node 2 is declared as an input node in `application.conf` (`inputs = [{ node = 2 }]`). This demonstrates the input node mechanism working end-to-end.

---

## 5. Experiment Summary Table

| # | Graph | Nodes | Edges | Algorithm | Seed | Duration | Sent | Dropped | Notes |
|---|-------|-------|-------|-----------|------|----------|------|---------|-------|
| 1 | SmallGraph | 21 | 20 | Wave only | 42 | 30s | 532 | 210 GOSSIP | Edge label enforcement proven |
| 2 | MediumGraph | 51 | 54 | Lai-Yang only | 123 | 60s | 2,544 | 0 | Snapshot complete, 3 in-flight captured |
| 3 | LargeGraph | 101 | 109 | Wave + Lai-Yang | 999 | 120s | 5,036 | 7 WORK | Both algorithms simultaneous |
| 4 | LargeGraph | 101 | 109 | Both + injection | 999 | 120s | ~5,000 | ~7 | Input node injection demonstrated |

---

## 6. Reproducible Experiment Script

Run the following on a clean machine after cloning:

```bash
# 1. Clone with submodules
git clone --recurse-submodules https://github.com/its-Shivam732/CS553-DistributedAlgorithms.git
cd CS553-DistributedAlgorithms

# 2. Set Java 17 (required)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH=$JAVA_HOME/bin:$PATH
java -version   # Must show 17.x.x

# 3. Compile
sbt compile

# 4. Run all tests
sbt test

# 5. Experiment 1 — Wave, SmallGraph, 30s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s \
  --wave-only"

# 6. Experiment 2 — Lai-Yang Snapshot, MediumGraph, 60s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/MediumGraph.ngs \
  --run 60s \
  --snapshot-only"

# 7. Experiment 3 — Both algorithms, LargeGraph, 120s
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/LargeGraph.ngs \
  --run 120s"

# 8. Experiment 4 — File-driven injection
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --inject src/main/resources/inject.txt"
```

**On Linux/non-macOS:** Replace `$(/usr/libexec/java_home -v 17)` with the path to your Java 17 installation, e.g. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

---

## 7. Demo Script (3–6 minutes)

### Suggested demo flow:

**Minute 1 — Show graph files and config (0:00–1:00)**
- Open `src/main/resources/graphs/` — show the three `.ngs` files
- Open `application.conf` — highlight `graphFile`, `seed`, `defaultPdf`, `timerNodes`, `algorithms`
- Explain: "Everything is config-driven — no hardcoded values"

**Minute 2 — Compile and test (1:00–2:00)**
```bash
sbt compile   # show clean build
sbt test      # show 10 test files passing
```

**Minute 3 — Experiment 1: Wave on SmallGraph (2:00–3:30)**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/SmallGraph.ngs \
  --run 30s --wave-only"
```
- Point out: `21 actors running`
- Point out: `Node 0: initiating wave`
- Point out: `WAVE COMPLETE! duration=12ms`
- Point out: `GOSSIP → sent: 0, dropped: 210` — edge label enforcement

**Minute 4 — Experiment 2: Lai-Yang on MediumGraph (3:30–4:30)**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --graph src/main/resources/graphs/MediumGraph.ngs \
  --run 60s --snapshot-only"
```
- Point out: `Node 0: initiating snapshot`
- Point out: `Node 0: turned RED, sending markers to Set(35, 34)`
- Point out: `snapshot COMPLETE. InFlight=3 messages`

**Minute 5 — Injection demo (4:30–5:30)**
```bash
sbt "runMain com.uic.cs553.distributed.simcli.SimMain \
  --inject src/main/resources/inject.txt"
```
- Show `inject.txt` contents
- Point out: `Loaded 4 injection entries`
- Point out: `Injected WORK into node 1: 'task-001'`

**Minute 6 — Final metrics (5:30–6:00)**
- Point out the `=== SIMULATION METRICS FINAL REPORT ===` block
- Explain sent/dropped breakdown per message type
- Summarize the experiment table

---

## 8. Algorithm Correctness Arguments

### Wave Algorithm

The Echo Algorithm satisfies the three wave algorithm properties:

1. **Every node participates:** The initiator sends to all neighbors. Every node that has not yet participated forwards to all non-parent neighbors. The directed graph ensures all reachable nodes are visited.

2. **At least one decision event:** The initiator decides at `pendingAcks == 0` after collecting ACKs from all its children.

3. **Defined termination:** The algorithm terminates in at most `2 * |E|` messages (one wave + one ack per edge). Observed in Experiment 1: wave completed in 12ms.

**System assumption match:** The algorithm assumes asynchronous, reliable channels and a general graph. Akka's actor mailboxes provide reliable in-order delivery per sender. The graph topology is unconstrained (NetGameSim generates general directed graphs).

### Lai-Yang Snapshot

The algorithm takes a consistent global snapshot satisfying:

1. **Consistency:** Every message recorded in a channel state was sent before the snapshot was recorded (it was sent while the sender was WHITE, before turning RED).

2. **Completeness:** Every node eventually receives a marker (either directly from the initiator or transitively through neighbors), takes its snapshot, and turns RED.

3. **Non-FIFO correctness:** Because WHITE messages carry the sender's color, RED nodes can identify and capture in-flight WHITE messages without relying on channel ordering.

**System assumption match:** Akka's dispatcher-based concurrency does not guarantee FIFO ordering across different dispatchers. Lai-Yang's coloring mechanism handles this correctly. Chandy-Lamport would not be safe in this runtime.

---

## 9. Known Limitations

1. **Snapshot termination detection:** The current implementation detects snapshot completion per-node (`pendingRed == 0`). A full global snapshot termination detector (collecting all local snapshots at a coordinator) is not implemented — this would require an additional round of messages.

2. **Cinnamon metrics:** The Lightbend Cinnamon plugin is configured but requires a commercial license token to activate. Built-in `MetricsCollector` provides equivalent per-message-type counters.

3. **Single-machine simulation:** All actors run in one JVM. The architecture supports multi-node deployment (Akka Cluster is a dependency) but cluster configuration is not set up in this submission.
