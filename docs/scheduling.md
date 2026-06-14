# Placement and Reconciliation

The placement path ranks healthy GPU nodes and attempts reservations in score
order. A node must match the requested region, provide at least the requested GPU
profile tier, be healthy, and report available capacity.

## Score

```text
score =
  normalized available capacity * 0.45
  + latency fit              * 0.30
  + heartbeat freshness      * 0.20
  - current load penalty     * 0.15
```

This favors local low-latency nodes without pushing every new session onto the
same host.

## Oversubscription Defense

The Redis lease script performs these operations atomically:

1. read available slot count
2. initialize capacity if missing
3. reject when capacity is zero
4. decrement capacity
5. write a session lease with TTL

The lease value stores the reserved node id. Release uses Lua to compare the
lease value with the expected node id before deleting the lease and incrementing
that node's capacity.

## Reconciliation

The scheduled reconciler handles cases that do not complete on the request path:

- reservations that never become streaming sessions
- nodes that stop sending heartbeats
- abandoned sessions whose lease should release capacity
