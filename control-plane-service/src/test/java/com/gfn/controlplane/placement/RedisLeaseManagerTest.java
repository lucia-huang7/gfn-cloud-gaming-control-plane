package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import com.gfn.controlplane.node.RegisterNodeRequest;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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
        RedisLeaseManager manager = new RedisLeaseManager(redisTemplate, 45);
        GpuNode node = new GpuNode(new RegisterNodeRequest("node-1", Region.US_WEST, GpuProfile.ULTRA, 4, 20));

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("45"), eq("4"), eq("node-1")))
                .thenReturn(1L);

        assertThat(manager.tryReserve(node, "sess-1")).isTrue();
        assertThat(node.availableSlots()).isEqualTo(4);
    }

    @Test
    void releaseUsesLuaSoMissingLeaseCannotOverIncrementCapacity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisLeaseManager manager = new RedisLeaseManager(redisTemplate, 45);

        manager.release("node-1", "sess-1");

        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of("node:node-1:available_slots", "session:sess-1:lease")));
    }
}

