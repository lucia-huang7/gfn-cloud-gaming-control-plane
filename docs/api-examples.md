# API Examples

Register a GPU node:

```bash
curl -X POST http://localhost:8080/api/v1/nodes/register \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"us-west-a10g-1","region":"US_WEST","gpuProfile":"ULTRA","totalSlots":4,"avgLatencyMs":24}'
```

Send a heartbeat:

```bash
curl -X POST http://localhost:8080/api/v1/nodes/us-west-a10g-1/heartbeat \
  -H 'Content-Type: application/json' \
  -d '{"availableSlots":4,"activeSessions":0}'
```

Create a session:

```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"userId":"user_123","gameId":"cyberpunk2077","region":"US_WEST","gpuProfile":"ULTRA","maxLatencyMs":45}'
```

Check capacity:

```bash
curl 'http://localhost:8080/api/v1/capacity?region=US_WEST&gpuProfile=ULTRA'
```

