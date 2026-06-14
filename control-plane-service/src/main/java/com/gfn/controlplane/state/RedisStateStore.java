package com.gfn.controlplane.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfn.controlplane.persistence.SessionEvent;
import com.gfn.controlplane.session.SessionStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    private static final String LUA_RELEASE_QUEUE_CLAIM = """
            local claimKey = KEYS[1]
            local claimToken = ARGV[1]
            if redis.call('GET', claimKey) == claimToken then
              return redis.call('DEL', claimKey)
            end
            return 0
            """;
    private static final String SESSION_PREFIX = "state:session:";
    private static final String SESSION_ACTIVE_INDEX = "state:sessions:active";
    private static final String SESSION_QUEUE_INDEX_PREFIX = "state:sessions:queued:";
    private static final String SESSION_RESERVED_EXPIRY_INDEX = "state:sessions:reserved:by_reservation_time";
    private static final String IDEMPOTENCY_PREFIX = "state:idempotency:";
    private static final String QUEUE_CLAIM_PREFIX = "queue-claim:session:";
    private static final String NODE_PREFIX = "state:node:";
    private static final String NODE_INDEX = "state:nodes";
    private static final String SESSION_EVENT_DEAD_LETTER_PREFIX = "deadletter:session-event:";
    private static final String AUTH_AUDIT_KEY = "audit:auth";
    private static final String RATE_LIMIT_PREFIX = "rate-limit:";
    private static final String NODE_CAPACITY_PREFIX = "node:";
    private static final String NODE_CAPACITY_SUFFIX = ":available_slots";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration terminalSessionRetention;
    private final Duration nodeRetention;

    public RedisStateStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${control-plane.terminal-session-retention-seconds:86400}") long terminalSessionRetentionSeconds,
            @Value("${control-plane.node-retention-seconds:3600}") long nodeRetentionSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.terminalSessionRetention = Duration.ofSeconds(terminalSessionRetentionSeconds);
        this.nodeRetention = Duration.ofSeconds(nodeRetentionSeconds);
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
        if (isTerminal(session.status())) {
            writeJson(SESSION_PREFIX + session.sessionId(), session, terminalSessionRetention);
            redisTemplate.opsForSet().remove(SESSION_ACTIVE_INDEX, session.sessionId());
            removeSessionRoutingIndexes(session);
            return;
        }
        writeJson(SESSION_PREFIX + session.sessionId(), session);
        redisTemplate.opsForSet().add(SESSION_ACTIVE_INDEX, session.sessionId());
        if (session.status() == SessionStatus.QUEUED) {
            redisTemplate.opsForZSet().add(queueIndexKey(session), session.sessionId(), session.createdAt().toEpochMilli());
            redisTemplate.opsForZSet().remove(SESSION_RESERVED_EXPIRY_INDEX, session.sessionId());
        } else if (session.status() == SessionStatus.RESERVED) {
            Instant reservationStartedAt = session.reservedAt() == null ? session.createdAt() : session.reservedAt();
            redisTemplate.opsForZSet().add(SESSION_RESERVED_EXPIRY_INDEX, session.sessionId(), reservationStartedAt.toEpochMilli());
            redisTemplate.opsForZSet().remove(queueIndexKey(session), session.sessionId());
        } else {
            removeSessionRoutingIndexes(session);
        }
    }

    public Optional<SessionSnapshot> findSession(String sessionId) {
        return readJson(SESSION_PREFIX + sessionId, SessionSnapshot.class);
    }

    public List<SessionSnapshot> listSessions() {
        return scanIndexedJson(SESSION_ACTIVE_INDEX, SESSION_PREFIX, SessionSnapshot.class);
    }

    public List<SessionSnapshot> listQueuedSessions(int limit) {
        List<SessionSnapshot> queued = new ArrayList<>();
        for (RegionShard shard : RegionShard.all()) {
            List<String> ids = rangeByRank(queueIndexKey(shard), 0, Math.max(0, limit - 1));
            for (String id : ids) {
                readQueuedSession(id, shard.indexKey()).ifPresent(queued::add);
            }
        }
        return queued.stream()
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .limit(limit)
                .toList();
    }

    public List<SessionSnapshot> listActiveReservationsBefore(Instant cutoff, int limit) {
        List<SessionSnapshot> reservations = new ArrayList<>();
        for (String id : rangeByScore(SESSION_RESERVED_EXPIRY_INDEX, 0, cutoff.toEpochMilli(), limit)) {
            Optional<SessionSnapshot> session = readJson(SESSION_PREFIX + id, SessionSnapshot.class);
            if (session.isPresent() && session.get().status() == SessionStatus.RESERVED) {
                reservations.add(session.get());
            } else {
                redisTemplate.opsForZSet().remove(SESSION_RESERVED_EXPIRY_INDEX, id);
            }
        }
        return reservations;
    }

    public Optional<String> claimQueuedSession(String sessionId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        boolean claimed = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(QUEUE_CLAIM_PREFIX + sessionId, token, ttl));
        return claimed ? Optional.of(token) : Optional.empty();
    }

    public void releaseQueuedSessionClaim(String sessionId, String claimToken) {
        redisTemplate.execute(
                new DefaultRedisScript<>(LUA_RELEASE_QUEUE_CLAIM, Long.class),
                List.of(QUEUE_CLAIM_PREFIX + sessionId),
                claimToken
        );
    }

    public void saveNode(NodeSnapshot node) {
        writeJson(NODE_PREFIX + node.nodeId(), node, nodeRetention);
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

    public void recordAuthAudit(Map<String, Object> event, Duration ttl, long maxEvents) {
        try {
            redisTemplate.opsForList().leftPush(AUTH_AUDIT_KEY, objectMapper.writeValueAsString(event));
            redisTemplate.opsForList().trim(AUTH_AUDIT_KEY, 0, Math.max(0, maxEvents - 1));
            redisTemplate.expire(AUTH_AUDIT_KEY, ttl);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize auth audit event", ex);
        }
    }

    private void writeJson(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize Redis state for key " + key, ex);
        }
    }

    private void writeJson(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
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
        List<T> values = new ArrayList<>();
        for (String id : scanSet(indexKey)) {
            Optional<T> value = readJson(valuePrefix + id, type);
            if (value.isPresent()) {
                values.add(value.get());
            } else {
                redisTemplate.opsForSet().remove(indexKey, id);
            }
        }
        return values;
    }

    private Optional<SessionSnapshot> readQueuedSession(String sessionId, String queueIndexKey) {
        Optional<SessionSnapshot> session = readJson(SESSION_PREFIX + sessionId, SessionSnapshot.class);
        if (session.isPresent() && session.get().status() == SessionStatus.QUEUED) {
            return session;
        }
        redisTemplate.opsForZSet().remove(queueIndexKey, sessionId);
        return Optional.empty();
    }

    private List<String> rangeByRank(String key, long start, long end) {
        return Optional.ofNullable(redisTemplate.opsForZSet().range(key, start, end))
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
    }

    private List<String> rangeByScore(String key, double min, double max, long limit) {
        return Optional.ofNullable(redisTemplate.opsForZSet().rangeByScore(key, min, max, 0, limit))
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
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

    private void removeSessionRoutingIndexes(SessionSnapshot session) {
        redisTemplate.opsForZSet().remove(queueIndexKey(session), session.sessionId());
        redisTemplate.opsForZSet().remove(SESSION_RESERVED_EXPIRY_INDEX, session.sessionId());
    }

    private String queueIndexKey(SessionSnapshot session) {
        return queueIndexKey(new RegionShard(session.region().name(), session.gpuProfile().name()));
    }

    private String queueIndexKey(RegionShard shard) {
        return shard.indexKey();
    }

    private boolean isTerminal(SessionStatus status) {
        return status == SessionStatus.TERMINATED
                || status == SessionStatus.EXPIRED
                || status == SessionStatus.FAILED;
    }

    private record IdempotencyValue(String sessionId, String requestFingerprint) {
    }

    private record RegionShard(String region, String gpuProfile) {
        static List<RegionShard> all() {
            List<RegionShard> shards = new ArrayList<>();
            for (com.gfn.controlplane.session.Region region : com.gfn.controlplane.session.Region.values()) {
                for (com.gfn.controlplane.session.GpuProfile gpuProfile : com.gfn.controlplane.session.GpuProfile.values()) {
                    shards.add(new RegionShard(region.name(), gpuProfile.name()));
                }
            }
            return shards;
        }

        String indexKey() {
            return SESSION_QUEUE_INDEX_PREFIX + region + ":" + gpuProfile;
        }
    }
}
