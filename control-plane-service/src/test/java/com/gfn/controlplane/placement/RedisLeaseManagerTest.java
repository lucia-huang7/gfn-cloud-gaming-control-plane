package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import com.gfn.controlplane.node.RegisterNodeRequest;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLeaseManagerTest {
    @Test
    void reserveUsesRedisLuaResultAsSourceOfTruth() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisLeaseManager manager = new RedisLeaseManager(redisTemplate);
        GpuNode node = new GpuNode(new RegisterNodeRequest("node-1", Region.US_WEST, GpuProfile.ULTRA, 4, 20));

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("4"), eq("node-1")))
                .thenReturn(1L);

        assertThat(manager.tryReserve(node, "sess-1")).isTrue();
        assertThat(node.availableSlots()).isEqualTo(4);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reserveLuaRejectsExistingSessionLeaseBeforeDecrementingCapacity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisLeaseManager manager = new RedisLeaseManager(redisTemplate);
        GpuNode node = new GpuNode(new RegisterNodeRequest("node-1", Region.US_WEST, GpuProfile.ULTRA, 4, 20));
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("4"), eq("node-1")))
                .thenReturn(0L);

        assertThat(manager.tryReserve(node, "sess-1")).isFalse();
        verify(redisTemplate).execute(
                script.capture(),
                eq(List.of("node:node-1:available_slots", "session:sess-1:lease")),
                eq("4"),
                eq("node-1")
        );
        assertThat(script.getValue().getScriptAsString())
                .contains("EXISTS", "leaseKey")
                .containsSubsequence("EXISTS', leaseKey", "return 0", "GET', capacityKey");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void releaseUsesLuaSoMissingOrMismatchedLeaseCannotOverIncrementCapacity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisLeaseManager manager = new RedisLeaseManager(redisTemplate);
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);

        manager.release("node-1", "sess-1");

        verify(redisTemplate).execute(
                script.capture(),
                eq(List.of("node:node-1:available_slots", "session:sess-1:lease")),
                eq("node-1")
        );
        assertThat(script.getValue().getScriptAsString())
                .contains("expectedNodeId", "GET", "leaseKey")
                .containsSubsequence("GET', leaseKey", "expectedNodeId", "DEL', leaseKey", "INCR', capacityKey");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reserveLuaDoesNotExpireLeaseBeforeReconcilerCanReleaseCapacity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisLeaseManager manager = new RedisLeaseManager(redisTemplate);
        GpuNode node = new GpuNode(new RegisterNodeRequest("node-1", Region.US_WEST, GpuProfile.ULTRA, 4, 20));
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("4"), eq("node-1")))
                .thenReturn(1L);

        manager.tryReserve(node, "sess-1");

        verify(redisTemplate).execute(
                script.capture(),
                eq(List.of("node:node-1:available_slots", "session:sess-1:lease")),
                eq("4"),
                eq("node-1")
        );
        assertThat(script.getValue().getScriptAsString())
                .contains("SET', leaseKey")
                .doesNotContain("'EX'")
                .doesNotContain("ttlSeconds");
    }
}
