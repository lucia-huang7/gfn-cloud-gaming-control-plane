package com.gfn.controlplane.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfn.controlplane.persistence.SessionEvent;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisStateStore {
    private static final String SESSION_PREFIX = "state:session:";
    private static final String SESSION_INDEX = "state:sessions";
    private static final String IDEMPOTENCY_PREFIX = "state:idempotency:";
    private static final String QUEUE_CLAIM_PREFIX = "queue-claim:session:";
    private static final String NODE_PREFIX = "state:node:";
    private static final String NODE_INDEX = "state:nodes";
    private static final String SESSION_EVENT_DEAD_LETTER_PREFIX = "deadletter:session-event:";
    private static final String RATE_LIMIT_PREFIX = "rate-limit:";
    private static final String NODE_CAPACITY_PREFIX = "node:";
    private static final String NODE_CAPACITY_SUFFIX = ":available_slots";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<String> getSessionIdForIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(readIdempotencyValue(IDEMPOTENCY_PREFIX + idempotencyKey))
                .map(IdempotencyValue::sessionId);
    }

    public IdempotencyClaim claimIdempotencyKey(String idempotencyKey, String sessionId, String requestFingerprint, Duration ttl) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        boolean claimed = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                key,
                serializeIdempotencyValue(new IdempotencyValue(sessionId, requestFingerprint)),
                ttl
        ));
        if (claimed) {
            return new IdempotencyClaim(true, sessionId, requestFingerprint);
        }
        IdempotencyValue existing = readIdempotencyValue(key);
        if (existing == null) {
            return claimIdempotencyKey(idempotencyKey, sessionId, requestFingerprint, ttl);
        }
        return new IdempotencyClaim(false, existing.sessionId(), existing.requestFingerprint());
    }

    public void releaseIdempotencyClaim(String idempotencyKey, String sessionId, String requestFingerprint) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        IdempotencyValue existing = readIdempotencyValue(key);
        if (existing != null
                && existing.sessionId().equals(sessionId)
                && Objects.equals(existing.requestFingerprint(), requestFingerprint)) {
            redisTemplate.delete(key);
        }
    }

    public void saveSession(SessionSnapshot session) {
        writeJson(SESSION_PREFIX + session.sessionId(), session);
        redisTemplate.opsForSet().add(SESSION_INDEX, session.sessionId());
    }

    public Optional<SessionSnapshot> findSession(String sessionId) {
        return readJson(SESSION_PREFIX + sessionId, SessionSnapshot.class);
    }

    public List<SessionSnapshot> listSessions() {
        return scanIndexedJson(SESSION_INDEX, SESSION_PREFIX, SessionSnapshot.class);
    }

    public boolean claimQueuedSession(String sessionId, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(QUEUE_CLAIM_PREFIX + sessionId, "claimed", ttl));
    }

    public void releaseQueuedSessionClaim(String sessionId) {
        redisTemplate.delete(QUEUE_CLAIM_PREFIX + sessionId);
    }

    public void saveNode(NodeSnapshot node) {
        writeJson(NODE_PREFIX + node.nodeId(), node);
        redisTemplate.opsForSet().add(NODE_INDEX, node.nodeId());
    }

    public void setNodeAvailableSlots(String nodeId, int availableSlots) {
        redisTemplate.opsForValue().set(capacityKey(nodeId), String.valueOf(availableSlots));
    }

    public Optional<Integer> getNodeAvailableSlots(String nodeId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(capacityKey(nodeId))).map(Integer::parseInt);
    }

    public Optional<NodeSnapshot> findNode(String nodeId) {
        return readJson(NODE_PREFIX + nodeId, NodeSnapshot.class);
    }

    public List<NodeSnapshot> listNodes() {
        return scanIndexedJson(NODE_INDEX, NODE_PREFIX, NodeSnapshot.class);
    }

    public void deadLetterSessionEvent(SessionEvent event, RuntimeException failure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deadLetteredAt", Instant.now().toString());
        payload.put("sessionId", event.getKey().getSessionId());
        payload.put("eventId", event.getKey().getEventId().toString());
        payload.put("eventCreatedAt", event.getKey().getCreatedAt().toString());
        payload.put("eventType", event.getEventType());
        payload.put("region", event.getRegion());
        payload.put("gpuProfile", event.getGpuProfile());
        payload.put("nodeId", event.getNodeId());
        payload.put("failureType", failure.getClass().getName());
        payload.put("failureMessage", failure.getMessage());
        writeJson(SESSION_EVENT_DEAD_LETTER_PREFIX + UUID.randomUUID(), payload);
    }

    public long incrementRateLimit(String key, Duration ttl) {
        String redisKey = RATE_LIMIT_PREFIX + key;
        Long value = redisTemplate.opsForValue().increment(redisKey);
        if (value != null && value == 1L) {
            redisTemplate.expire(redisKey, ttl);
        }
        return value == null ? 0 : value;
    }

    private void writeJson(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Redis state for key " + key, ex);
        }
    }

    private String serializeIdempotencyValue(IdempotencyValue value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Redis idempotency value", ex);
        }
    }

    private IdempotencyValue readIdempotencyValue(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, IdempotencyValue.class);
        } catch (JsonProcessingException ex) {
            return new IdempotencyValue(value, null);
        }
    }

    private <T> Optional<T> readJson(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize Redis state for key " + key, ex);
        }
    }

    private <T> List<T> scanIndexedJson(String indexKey, String valuePrefix, Class<T> type) {
        return scanSet(indexKey).stream()
                .map(id -> readJson(valuePrefix + id, type).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private List<String> scanSet(String key) {
        ScanOptions options = ScanOptions.scanOptions().count(500).build();
        List<String> values = new ArrayList<>();
        try (Cursor<String> cursor = Objects.requireNonNull(redisTemplate.opsForSet().scan(key, options))) {
            cursor.forEachRemaining(values::add);
        }
        return values;
    }

    private String capacityKey(String nodeId) {
        return NODE_CAPACITY_PREFIX + nodeId + NODE_CAPACITY_SUFFIX;
    }

    private record IdempotencyValue(String sessionId, String requestFingerprint) {
    }
}
