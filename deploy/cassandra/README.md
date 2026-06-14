# Cassandra Schema

Schema is managed outside the Spring Boot application.

Production deployments should run `migrations/001_session_events.cql` through a
migration job or schema deployment pipeline. Local Docker Compose uses
`local/001_session_events_local.cql` so the single-node development Cassandra can
keep RF=1 without weakening the production migration.

## Replication

The production migration uses `NetworkTopologyStrategy` with RF=3 in two
datacenters:

```text
'us-west': 3,
'us-east': 3
```

The local-only override uses RF=1 for Docker Compose's single `datacenter1`:

```text
'datacenter1': 1
```

## Retention

`session_events_by_session` uses a 30-day TTL in this sample migration:

```text
default_time_to_live = 2592000
```

Production retention should be set from audit, billing, and compliance
requirements rather than application defaults.

## Query Model

```text
session_events_by_session
partition key: session_id
clustering: created_at, event_id
```

Supported query:

```text
read all lifecycle events for one session in event-time order
```
