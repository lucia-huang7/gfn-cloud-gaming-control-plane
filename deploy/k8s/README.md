# Kubernetes Deployment

`control-plane.yaml` defines the control-plane runtime objects:

- `ServiceAccount`
- `ConfigMap`
- `Secret`
- `Deployment`
- `Service`
- `HorizontalPodAutoscaler`
- `PodDisruptionBudget`
- `NetworkPolicy`

Redis and Cassandra are not deployed by this manifest. In production they should
be managed as separate platform dependencies, such as managed services, operators,
or dedicated StatefulSets with their own backup, restore, storage, and upgrade
processes.

## External Dependencies

The control plane expects these endpoints from `gfn-control-plane-config`:

```text
REDIS_HOST
REDIS_PORT
CASSANDRA_CONTACT_POINTS
CASSANDRA_PORT
CASSANDRA_KEYSPACE
CASSANDRA_LOCAL_DATACENTER
```

Secrets should be injected through a real secret manager:

```text
CASSANDRA_USERNAME
CASSANDRA_PASSWORD
REDIS_PASSWORD
```

The Spring Boot configuration wires those credentials into Redis/Cassandra
clients through `application.yml`.

## Scaling

The control plane can run multiple replicas because session state, idempotency
claims, node registry, capacity counters, and leases are stored in Redis.

HPA starts at two replicas and scales on CPU/memory. Production autoscaling should
add request-rate and placement-latency metrics.

## Network Policy

The included policy allows:

- ingress from an ingress namespace or labeled platform pods
- egress to kube-dns
- egress to namespaces labeled `gfn.nvidia.com/data-plane=true` on Redis and Cassandra ports

Adjust namespace labels to match the target cluster.
