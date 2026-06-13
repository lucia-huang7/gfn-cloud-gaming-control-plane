package com.gfn.controlplane.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfn.controlplane.persistence.SessionEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class RedisStateStore {
    private static final String SESSION_PREFIX = "state:session:";
    private static final String IDEMPOTENCY_PREFIX = "state:idempotency:";
    private static final String QUEUE_CLAIM_PREFIX = "queue-claim:session:";
    private static final String NODE_PREFIX = "state:node:";
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
        return Optional.ofNullable(redisTemplate.opsForValue().get(IDEMPOTENCY_PREFIX + idempotencyKey));
    }

    public IdempotencyClaim claimIdempotencyKey(String idempotencyKey, String sessionId, Duration ttl) {
        String key = IDEMPOTENCY_PREFIX + idempotencyKey;
        boolean claimed = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, sessionId, ttl));
        if (claimed) {
            return new IdempotencyClaim(true, sessionId);
        }
        String existingSessionId = redisTemplate.opsForValue().get(key);
        if (existingSessionId == null) {
            return claimIdempotencyKey(idempotencyKey, sessionId, ttl);
        }
        return new IdempotencyClaim(false, existingSessionId);
    }

    public void saveSession(SessionSnapshot session) {
        writeJson(SESSION_PREFIX + session.sessionId(), session);
    }

    public Optional<SessionSnapshot> findSession(String sessionId) {
        return readJson(SESSION_PREFIX + sessionId, SessionSnapshot.class);
    }

    public List<SessionSnapshot> listSessions() {
        return scanJson(SESSION_PREFIX + "*", SessionSnapshot.class);
    }

    public boolean claimQueuedSession(String sessionId, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(QUEUE_CLAIM_PREFIX + sessionId, "claimed", ttl));
    }

    public void releaseQueuedSessionClaim(String sessionId) {
        redisTemplate.delete(QUEUE_CLAIM_PREFIX + sessionId);
    }

    public void saveNode(NodeSnapshot node) {
        writeJson(NODE_PREFIX + node.nodeId(), node);
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
        return scanJson(NODE_PREFIX + "*", NodeSnapshot.class);
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

    private <T> List<T> scanJson(String pattern, Class<T> type) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(key -> readJson(key, type).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    private String capacityKey(String nodeId) {
        return NODE_CAPACITY_PREFIX + nodeId + NODE_CAPACITY_SUFFIX;
    }
}
