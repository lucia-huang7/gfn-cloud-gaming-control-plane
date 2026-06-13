package com.gfn.controlplane.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gfn.controlplane.node.NodeStatus;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import com.gfn.controlplane.session.SessionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStateStoreTest {
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RedisStateStore stateStore = new RedisStateStore(redisTemplate, objectMapper);

    @Test
    void saveSessionMaintainsSecondaryIndex() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        stateStore.saveSession(session("sess-1"));

        verify(valueOperations).set(eq("state:session:sess-1"), any(String.class));
        verify(setOperations).add("state:sessions", "sess-1");
    }

    @Test
    void saveNodeMaintainsSecondaryIndex() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        stateStore.saveNode(node("node-1"));

        verify(valueOperations).set(eq("state:node:node-1"), any(String.class));
        verify(setOperations).add("state:nodes", "node-1");
    }

    @Test
    void idempotencyClaimStoresRequestFingerprint() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("state:idempotency:tenant-a:idem-1"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(true);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);

        IdempotencyClaim claim = stateStore.claimIdempotencyKey(
                "tenant-a:idem-1",
                "sess-1",
                "fingerprint-1",
                Duration.ofMinutes(10)
        );

        assertThat(claim).isEqualTo(new IdempotencyClaim(true, "sess-1", "fingerprint-1"));
        verify(valueOperations).setIfAbsent(eq("state:idempotency:tenant-a:idem-1"), value.capture(), eq(Duration.ofMinutes(10)));
        assertThat(value.getValue()).contains("sess-1", "fingerprint-1");
    }

    @Test
    void idempotencyClaimReturnsExistingFingerprint() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("state:idempotency:tenant-a:idem-1"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(false);
        when(valueOperations.get("state:idempotency:tenant-a:idem-1"))
                .thenReturn("{\"sessionId\":\"sess-existing\",\"requestFingerprint\":\"fingerprint-existing\"}");

        IdempotencyClaim claim = stateStore.claimIdempotencyKey(
                "tenant-a:idem-1",
                "sess-new",
                "fingerprint-new",
                Duration.ofMinutes(10)
        );

        assertThat(claim).isEqualTo(new IdempotencyClaim(false, "sess-existing", "fingerprint-existing"));
    }

    @Test
    void idempotencyClaimStillReadsLegacySessionIdValues() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("state:idempotency:tenant-a:idem-1"), any(String.class), eq(Duration.ofMinutes(10))))
                .thenReturn(false);
        when(valueOperations.get("state:idempotency:tenant-a:idem-1")).thenReturn("sess-existing");

        IdempotencyClaim claim = stateStore.claimIdempotencyKey(
                "tenant-a:idem-1",
                "sess-new",
                "fingerprint-new",
                Duration.ofMinutes(10)
        );

        assertThat(claim).isEqualTo(new IdempotencyClaim(false, "sess-existing", null));
    }

    @Test
    void releaseIdempotencyClaimDeletesOnlyMatchingClaim() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("state:idempotency:tenant-a:idem-1"))
                .thenReturn("{\"sessionId\":\"sess-1\",\"requestFingerprint\":\"fingerprint-1\"}");

        stateStore.releaseIdempotencyClaim("tenant-a:idem-1", "sess-1", "fingerprint-1");

        verify(redisTemplate).delete("state:idempotency:tenant-a:idem-1");
    }

    @Test
    void releaseIdempotencyClaimKeepsMismatchedClaim() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("state:idempotency:tenant-a:idem-1"))
                .thenReturn("{\"sessionId\":\"sess-2\",\"requestFingerprint\":\"fingerprint-2\"}");

        stateStore.releaseIdempotencyClaim("tenant-a:idem-1", "sess-1", "fingerprint-1");

        verify(redisTemplate, never()).delete("state:idempotency:tenant-a:idem-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSessionsScansSecondaryIndexInsteadOfUsingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        SessionSnapshot session = session("sess-1");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.scan(eq("state:sessions"), any(ScanOptions.class))).thenReturn(cursor);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(0);
            consumer.accept("sess-1");
            return null;
        }).when(cursor).forEachRemaining(any());
        when(valueOperations.get("state:session:sess-1")).thenReturn(objectMapper.writeValueAsString(session));

        List<SessionSnapshot> sessions = stateStore.listSessions();

        assertThat(sessions).containsExactly(session);
        verify(setOperations).scan(eq("state:sessions"), any(ScanOptions.class));
        verify(redisTemplate, never()).keys(any(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listNodesScansSecondaryIndexInsteadOfUsingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        NodeSnapshot node = node("node-1");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.scan(eq("state:nodes"), any(ScanOptions.class))).thenReturn(cursor);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(0);
            consumer.accept("node-1");
            return null;
        }).when(cursor).forEachRemaining(any());
        when(valueOperations.get("state:node:node-1")).thenReturn(objectMapper.writeValueAsString(node));

        List<NodeSnapshot> nodes = stateStore.listNodes();

        assertThat(nodes).containsExactly(node);
        verify(setOperations).scan(eq("state:nodes"), any(ScanOptions.class));
        verify(redisTemplate, never()).keys(any(String.class));
    }

    private SessionSnapshot session(String sessionId) {
        return new SessionSnapshot(
                sessionId,
                "tenant-a",
                "user_123",
                "cyberpunk2077",
                Region.US_WEST,
                GpuProfile.ULTRA,
                45,
                Instant.parse("2026-06-13T00:00:00Z"),
                SessionStatus.QUEUED,
                null
        );
    }

    private NodeSnapshot node(String nodeId) {
        return new NodeSnapshot(
                nodeId,
                Region.US_WEST,
                GpuProfile.ULTRA,
                8,
                8,
                0,
                20,
                Instant.parse("2026-06-13T00:00:00Z"),
                NodeStatus.HEALTHY
        );
    }
}
