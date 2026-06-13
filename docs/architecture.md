# Architecture

## Components

```text
SessionController
  POST /api/v1/sessions
  GET /api/v1/sessions/{id}
  DELETE /api/v1/sessions/{id}

ApiAuthFilter
  authenticates client, node, and admin callers by token
  enforces endpoint authorization by caller role
  requires tenant id for client session APIs
  applies Redis-backed per-tenant session-create rate limiting

NodeController
  POST /api/v1/nodes/register
  POST /api/v1/nodes/{id}/heartbeat
  GET /api/v1/nodes

PlacementService
  filters nodes by region, GPU profile, health, latency SLA, and free capacity
  ranks candidates with CapacityScorer
  calls RedisLeaseManager for reservation

RedisLeaseManager
  stores capacity counters
  writes session lease keys with TTL
  uses Lua for check/decrement/lease as one Redis operation
  uses Lua for delete-lease/increment-capacity as one release operation

RedisStateStore
  stores session records
  claims idempotency keys with SET NX and a bounded TTL
  stores node registry and heartbeat snapshots
  keeps heartbeat snapshots separate from reservation capacity counters
  reads schedulable node capacity from Redis counters
  stores session event dead letters when Cassandra writes fail

QueueReconciler
  marks stale nodes
  expires old RESERVED sessions
  releases abandoned slots
  drains QUEUED sessions in FIFO order

Cassandra
  stores append-only session events
  schema is applied by migration CQL, not by app startup

SessionEventPublisher
  writes session events to Cassandra
  logs Cassandra write failures
  increments persisted/dead-lettered metrics
  writes failed events to Redis dead-letter storage
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
  |-- Redis idempotency claim with SET NX
  |-- create SessionRecord
  |
  v
PlacementService
  |-- list healthy nodes
  |-- score candidates
  |
  v
RedisLeaseManager
  |-- reject existing session lease
  |-- DECR node capacity
  |-- SET session lease EX ttl
  |
  v
SessionEventRepository
  |
  v
Cassandra
  |
  | on write failure
  v
Redis dead letter
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

Queued sessions keep the original placement request fields in Redis, including
region, GPU profile, and max latency. The reconciler can retry placement without
requiring another client request.

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
node.avgLatencyMs <= request.maxLatencyMs
```

`maxLatencyMs` is treated as an SLA gate. Nodes above the requested latency are
not placement candidates; latency scoring only ranks nodes that already satisfy
the SLA.

## Redis Keys

```text
state:session:{sessionId}
state:idempotency:{tenantId}:{idempotencyKey}
state:node:{nodeId}                 # metadata and heartbeat snapshot
deadletter:session-event:{uuid}
rate-limit:client-create-session:{tenantId}
node:{nodeId}:available_slots
session:{sessionId}:lease
```

## Caller Model

```text
CLIENT
  POST /api/v1/sessions
  GET  /api/v1/sessions/{id}
  GET  /api/v1/capacity

NODE
  POST /api/v1/nodes/register
  POST /api/v1/nodes/{id}/heartbeat

ADMIN
  all API endpoints
```

Clients must send `X-Tenant-Id`. Sessions store `tenantId`, idempotency keys are
tenant-scoped, and non-admin reads hide sessions from other tenants.

## Event Persistence Failure Path

Session event writes are not silently ignored. `SessionEventPublisher` records:

```text
gfn_session_events_persisted_total
gfn_session_events_dead_lettered_total
```

If Cassandra is unavailable, the failed event and exception summary are written to:

```text
deadletter:session-event:{uuid}
```

## Idempotency

`POST /api/v1/sessions` first claims `state:idempotency:{key}` with Redis
`SET NX EX`. The winner owns session creation. Concurrent requests with the same
key read the claimed session id and wait briefly for the session record to become
visible instead of creating another session.

Reservation Lua script:

```text
GET capacity
if missing, initialize capacity
if capacity <= 0, reject
DECR capacity
SET lease EX ttl
return reserved
```

Release Lua script:

```text
DEL lease
if deleted, INCR capacity
if lease missing, do not increment
```

## Cassandra Table

Schema is managed outside the application in:

```text
deploy/cassandra/migrations/001_session_events.cql
```

The Spring Boot service runs with `SchemaAction.NONE`; it expects the keyspace and
tables to exist before startup. Local Docker Compose applies the migration with a
one-shot `cassandra-schema` job.

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

Local development uses `NetworkTopologyStrategy` with RF=1 for `datacenter1` and
a 30-day TTL. Production should set RF per datacenter and event retention from
audit, billing, and compliance requirements.

## Reconciliation

Runs on a fixed delay:

```text
mark nodes STALE when last heartbeat is older than heartbeat-timeout
expire RESERVED sessions older than reservation-ttl
release slot for expired reservation
retry QUEUED sessions in created_at order
claim each queued session through Redis SET NX before retry
stop draining when the first queued session cannot be placed
write terminal session event
```

## Replica Safety

The control plane deployment uses two replicas. Runtime control-plane state is
shared through Redis:

```text
sessions       -> state:session:{sessionId}
session index  -> state:sessions
idempotency    -> state:idempotency:{idempotencyKey}
node registry  -> state:node:{nodeId}
node index     -> state:nodes
capacity lease -> node:{nodeId}:available_slots and session:{sessionId}:lease
queue drain    -> queue-claim:session:{sessionId}
```

The service keeps no authoritative session, idempotency, or node registry state in
process memory.

Node heartbeat payloads update health and reported node snapshots only. They do
not reset `node:{nodeId}:available_slots`; that counter is initialized on node
registration and then changed only by reservation/release Lua scripts.

Every pod can run reconciliation. Before retrying a queued session, the pod must
claim `queue-claim:session:{sessionId}` with a short TTL and then re-read the
session. The reservation Lua script also checks whether
`session:{sessionId}:lease` already exists before decrementing capacity, so a
duplicate drain attempt cannot reserve the same session twice.

Session and node enumeration never uses Redis `KEYS`; writes maintain secondary
sets and readers page through those sets with `SSCAN`.
