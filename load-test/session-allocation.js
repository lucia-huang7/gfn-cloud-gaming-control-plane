import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 50,
  duration: '45s',
  thresholds: {
    http_req_duration: ['p(95)<250'],
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  for (let i = 0; i < 8; i++) {
    http.post(`${BASE_URL}/api/v1/nodes/register`, JSON.stringify({
      nodeId: `us-west-a10g-${i}`,
      region: 'US_WEST',
      gpuProfile: 'ULTRA',
      totalSlots: 8,
      avgLatencyMs: 20 + i,
    }), { headers: { 'Content-Type': 'application/json' } });
  }
}

export default function () {
  const id = `${__VU}-${__ITER}`;
  const res = http.post(`${BASE_URL}/api/v1/sessions`, JSON.stringify({
    userId: `user_${id}`,
    gameId: 'cyberpunk2077',
    region: 'US_WEST',
    gpuProfile: 'ULTRA',
    maxLatencyMs: 45,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-${id}`,
    },
  });

  check(res, {
    'accepted': (r) => r.status === 202,
    'has session id': (r) => r.json('sessionId') !== undefined,
  });
  sleep(1);
}

