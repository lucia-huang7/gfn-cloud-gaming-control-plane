# GFN Cloud Gaming Control Plane

Java Spring Boot control-plane service for GPU-backed cloud gaming session
allocation.

Independent educational project. Not affiliated with NVIDIA or GeForce NOW.

## Stack

- Java 17, Spring Boot 3
- Spring Web, Validation, Actuator
- Redis / Valkey for session leases and capacity counters
- Redis-backed session, idempotency, and node registry state
- Cassandra for session event history
- Docker Compose, Kubernetes manifests
- k6 load test

## Run

```bash
docker compose up --build
```

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

Health:

```text
http://localhost:8080/actuator/health
```

## Example

Register a GPU node:

```bash
curl -X POST http://localhost:8080/api/v1/nodes/register \
  -H 'Content-Type: application/json' \
  -H 'X-Control-Plane-Token: dev-node-token' \
  -d '{"nodeId":"us-west-a10g-1","region":"US_WEST","gpuProfile":"ULTRA","totalSlots":4,"avgLatencyMs":24}'
```

Create a session:

```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -H 'X-Control-Plane-Token: dev-client-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"userId":"user_123","gameId":"cyberpunk2077","region":"US_WEST","gpuProfile":"ULTRA","maxLatencyMs":45}'
```

## Caller Model

```text
client token -> create/read own tenant sessions, read capacity
node token   -> register nodes and send heartbeats
admin token  -> all endpoints, including terminate and node inventory
```

Client requests must include `X-Tenant-Id`. Idempotency keys are scoped by tenant,
and session reads are tenant-checked unless the caller is admin.

## Endpoints

```text
POST   /api/v1/sessions
GET    /api/v1/sessions/{sessionId}
DELETE /api/v1/sessions/{sessionId}

POST   /api/v1/nodes/register
POST   /api/v1/nodes/{nodeId}/heartbeat
GET    /api/v1/nodes
GET    /api/v1/capacity?region=US_WEST&gpuProfile=ULTRA

GET    /actuator/health
GET    /actuator/prometheus
```

## Core Flow

```text
POST /sessions
  -> SessionService checks Idempotency-Key
  -> PlacementService ranks healthy GPU nodes
  -> RedisLeaseManager reserves one slot atomically
  -> Session is RESERVED or QUEUED
  -> SessionEvent is written to Cassandra
```

## Modules

```text
session/      REST API and session lifecycle
node/         GPU node registry and heartbeat state
placement/   node scoring and Redis lease reservation
queueing/    reservation expiry and stale-node reconciliation
persistence/ Cassandra session event model
state/       Redis-backed runtime state
config/      API errors and Cassandra setup
```

## Tests

```bash
docker run --rm \
  -v "$PWD/control-plane-service:/workspace" \
  -w /workspace \
  maven:3.9-eclipse-temurin-17 \
  mvn test
```

## Load Test

```bash
k6 run load-test/session-allocation.js
```
