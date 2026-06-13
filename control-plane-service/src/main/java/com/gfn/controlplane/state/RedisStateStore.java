package com.gfn.controlplane.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class RedisStateStore {
    private static final String SESSION_PREFIX = "state:session:";
    private static final String IDEMPOTENCY_PREFIX = "state:idempotency:";
    private static final String NODE_PREFIX = "state:node:";
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
