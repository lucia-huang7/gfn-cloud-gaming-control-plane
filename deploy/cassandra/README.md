# Cassandra Schema

Schema is managed outside the Spring Boot application.

Local Docker Compose runs `migrations/001_session_events.cql` with `cqlsh` before
starting the control-plane service. Production deployments should run the same CQL
through a migration job or schema deployment pipeline.

## Replication

The local migration uses `NetworkTopologyStrategy` with RF=1 for the single
Docker Compose datacenter:

```text
'datacenter1': 1
```

Production should use an RF per datacenter, for example:

```text
'us-west': 3,
'us-east': 3
```

## Retention

`session_events_by_session` uses a 30-day TTL in local development:

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

