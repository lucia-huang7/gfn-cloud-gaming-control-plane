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
const CLIENT_TOKEN = __ENV.CLIENT_TOKEN || 'dev-client-token';
const NODE_AUTH_TOKEN = __ENV.NODE_AUTH_TOKEN || 'dev-node-token';
const TENANT_ID = __ENV.TENANT_ID || 'tenant-a';
const NODE_CREDENTIAL = __ENV.NODE_CREDENTIAL;
const NODE_CREDENTIAL_VERSION = __ENV.NODE_CREDENTIAL_VERSION;

function nodeHeaders(nodeId) {
  const headers = {
    'Content-Type': 'application/json',
    'X-Control-Plane-Token': NODE_AUTH_TOKEN,
    'X-Node-Id': nodeId,
  };
  if (NODE_CREDENTIAL && NODE_CREDENTIAL_VERSION) {
    headers['X-Node-Credential'] = NODE_CREDENTIAL;
    headers['X-Node-Credential-Version'] = NODE_CREDENTIAL_VERSION;
  }
  return headers;
}

export function setup() {
  for (let i = 0; i < 8; i++) {
    const nodeId = `us-west-a10g-${i}`;
    const res = http.post(`${BASE_URL}/api/v1/nodes/register`, JSON.stringify({
      nodeId,
      region: 'US_WEST',
      gpuProfile: 'ULTRA',
      totalSlots: 8,
      avgLatencyMs: 20 + i,
    }), { headers: nodeHeaders(nodeId) });
    check(res, {
      [`registered ${nodeId}`]: (r) => r.status === 200,
    });
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
      'X-Control-Plane-Token': CLIENT_TOKEN,
      'X-Tenant-Id': TENANT_ID,
      'Idempotency-Key': `k6-${id}`,
    },
  });

  check(res, {
    'accepted': (r) => r.status === 202,
    'has session id': (r) => r.json('sessionId') !== undefined,
  });
  sleep(1);
}
