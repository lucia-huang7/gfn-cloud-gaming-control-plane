# Architecture

## Components

```text
SessionController
  POST /api/v1/sessions
  GET /api/v1/sessions/{id}
  DELETE /api/v1/sessions/{id}

NodeController
  POST /api/v1/nodes/register
  POST /api/v1/nodes/{id}/heartbeat
  GET /api/v1/nodes

PlacementService
  filters nodes by region, GPU profile, health, and free capacity
  ranks candidates with CapacityScorer
  calls RedisLeaseManager for reservation

RedisLeaseManager
  stores capacity counters
  writes session lease keys with TTL
  uses Lua for check/decrement/lease as one Redis operation

RedisStateStore
  stores session records
  stores idempotency keys with SETNX
  stores node registry and heartbeat snapshots
  reads node capacity from Redis counters

QueueReconciler
  marks stale nodes
  expires old RESERVED sessions
  releases abandoned slots

Cassandra
  stores append-only session events
```

## Request Flow

```text
client
  |
  | POST /api/v1/sessions
  v
SessionController
  |
  v
SessionService
  |-- Redis idempotency lookup / SETNX
  |-- create SessionRecord
  |
  v
PlacementService
  |-- list healthy nodes
  |-- score candidates
  |
  v
RedisLeaseManager
  |-- DECR node capacity
  |-- SET session lease EX ttl
  |
  v
SessionEventRepository
  |
  v
Cassandra
```

## Session States

```text
QUEUED
RESERVED
STARTING
STREAMING
TERMINATING
TERMINATED
EXPIRED
FAILED
```

Implemented path in v1:

```text
QUEUED -> RESERVED
RESERVED -> TERMINATED
RESERVED -> EXPIRED
QUEUED -> TERMINATED
```

## Placement Score

```text
score =
  available_capacity_ratio * 0.45
  + latency_fit            * 0.30
  + heartbeat_freshness    * 0.20
  - load_penalty           * 0.15
```

Candidate requirements:

```text
node.region == request.region
node.gpuProfile >= request.gpuProfile
node.status == HEALTHY
node.availableSlots > 0
```

## Redis Keys

```text
state:session:{sessionId}
state:idempotency:{idempotencyKey}
state:node:{nodeId}                 # metadata and heartbeat snapshot
node:{nodeId}:available_slots
session:{sessionId}:lease
```

Reservation Lua script:

```text
GET capacity
if missing, initialize capacity
if capacity <= 0, reject
DECR capacity
SET lease EX ttl
return reserved
```

## Cassandra Table

```text
session_events_by_session

partition key:
  session_id

clustering columns:
  created_at
  event_id

columns:
  event_type
  region
  gpu_profile
  node_id
```

## Reconciliation

Runs on a fixed delay:

```text
mark nodes STALE when last heartbeat is older than heartbeat-timeout
expire RESERVED sessions older than reservation-ttl
release slot for expired reservation
write terminal session event
```

## Replica Safety

The control plane deployment uses two replicas. Runtime control-plane state is
shared through Redis:

```text
sessions       -> state:session:{sessionId}
idempotency    -> state:idempotency:{idempotencyKey}
node registry  -> state:node:{nodeId}
capacity lease -> node:{nodeId}:available_slots and session:{sessionId}:lease
```

The service keeps no authoritative session, idempotency, or node registry state in
process memory.
