#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
NODE_ID="${NODE_ID:-it-us-west-a10g-1}"
CLIENT_TOKEN="${CLIENT_TOKEN:-dev-client-token}"
NODE_TOKEN="${NODE_TOKEN:-dev-node-token}"
TENANT_ID="${TENANT_ID:-tenant-it}"
COMPOSE="${COMPOSE:-docker compose}"

cleanup() {
  if [[ "${KEEP_STACK:-0}" != "1" ]]; then
    (cd "$ROOT_DIR" && $COMPOSE down --remove-orphans >/dev/null 2>&1 || true)
  fi
}
trap cleanup EXIT

wait_for_http() {
  local url="$1"
  local attempts="${2:-90}"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

post_json() {
  local url="$1"
  local body="$2"
  shift 2
  curl -fsS -X POST "$url" \
    -H 'Content-Type: application/json' \
    "$@" \
    -d "$body"
}

extract_json_string() {
  local field="$1"
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$field"
}

retry_until() {
  local description="$1"
  shift
  for _ in $(seq 1 30); do
    if "$@" >/tmp/gfn-it-check.out 2>/tmp/gfn-it-check.err; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $description" >&2
  cat /tmp/gfn-it-check.out /tmp/gfn-it-check.err >&2 || true
  return 1
}

cd "$ROOT_DIR"
$COMPOSE up -d --build
wait_for_http "$BASE_URL/actuator/health"

missing_token_status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/capacity?region=US_WEST&gpuProfile=ULTRA")"
if [[ "$missing_token_status" != "401" ]]; then
  echo "Expected missing-token capacity request to return 401, got $missing_token_status" >&2
  exit 1
fi

node_body="$(cat <<JSON
{"nodeId":"$NODE_ID","region":"US_WEST","gpuProfile":"ULTRA","totalSlots":4,"avgLatencyMs":20}
JSON
)"
post_json "$BASE_URL/api/v1/nodes/register" "$node_body" \
  -H "X-Control-Plane-Token: $NODE_TOKEN" \
  -H "X-Node-Id: $NODE_ID" >/dev/null

session_body='{"userId":"user_it","gameId":"cyberpunk2077","region":"US_WEST","gpuProfile":"ULTRA","maxLatencyMs":45}'
session_response="$(
  post_json "$BASE_URL/api/v1/sessions" "$session_body" \
    -H "X-Control-Plane-Token: $CLIENT_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID" \
    -H "Idempotency-Key: it-001"
)"
session_id="$(printf '%s' "$session_response" | extract_json_string sessionId)"
status="$(printf '%s' "$session_response" | extract_json_string status)"
if [[ "$status" != "RESERVED" ]]; then
  echo "Expected session to be RESERVED, got $status: $session_response" >&2
  exit 1
fi

session_read="$(
  curl -fsS "$BASE_URL/api/v1/sessions/$session_id" \
    -H "X-Control-Plane-Token: $CLIENT_TOKEN" \
    -H "X-Tenant-Id: $TENANT_ID"
)"
read_status="$(printf '%s' "$session_read" | extract_json_string status)"
if [[ "$read_status" != "RESERVED" ]]; then
  echo "Expected session read to return RESERVED, got $read_status: $session_read" >&2
  exit 1
fi

lease_value="$($COMPOSE exec -T redis valkey-cli GET "session:${session_id}:lease" | tr -d '\r')"
if [[ "$lease_value" != "$NODE_ID" ]]; then
  echo "Expected Redis lease to point at $NODE_ID, got '$lease_value'" >&2
  exit 1
fi

available_slots="$($COMPOSE exec -T redis valkey-cli GET "node:${NODE_ID}:available_slots" | tr -d '\r')"
if [[ "$available_slots" != "3" ]]; then
  echo "Expected Redis capacity counter to be 3, got '$available_slots'" >&2
  exit 1
fi

retry_until "Cassandra session event" bash -c \
  "$COMPOSE exec -T cassandra cqlsh cassandra 9042 -e \"SELECT event_type FROM gfn_control_plane.session_events_by_session WHERE session_id='$session_id';\" | grep -q PLACEMENT_RESERVED"

echo "Integration smoke passed for session $session_id"
