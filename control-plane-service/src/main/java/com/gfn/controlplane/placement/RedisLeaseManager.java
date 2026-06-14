package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisLeaseManager {
    private static final String LUA_RESERVE = """
            local capacityKey = KEYS[1]
            local leaseKey = KEYS[2]
            if redis.call('EXISTS', leaseKey) == 1 then
              return 0
            end
            local capacity = tonumber(redis.call('GET', capacityKey) or '-1')
            if capacity < 0 then
              redis.call('SET', capacityKey, ARGV[2])
              capacity = tonumber(ARGV[2])
            end
            if capacity <= 0 then
              return 0
            end
            redis.call('DECR', capacityKey)
            redis.call('SET', leaseKey, ARGV[2])
            return 1
            """;
    private static final String LUA_RELEASE = """
            local capacityKey = KEYS[1]
            local leaseKey = KEYS[2]
            local expectedNodeId = ARGV[1]
            if redis.call('GET', leaseKey) == expectedNodeId then
              redis.call('DEL', leaseKey)
              redis.call('INCR', capacityKey)
              return 1
            end
            return 0
            """;

    private final StringRedisTemplate redisTemplate;

    public RedisLeaseManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryReserve(GpuNode node, String sessionId) {
        Long reserved = redisTemplate.execute(
                new DefaultRedisScript<>(LUA_RESERVE, Long.class),
                List.of(capacityKey(node.nodeId()), leaseKey(sessionId)),
                String.valueOf(node.availableSlots()),
                node.nodeId()
        );
        return Long.valueOf(1L).equals(reserved);
    }

    public void release(String nodeId, String sessionId) {
        redisTemplate.execute(
                new DefaultRedisScript<>(LUA_RELEASE, Long.class),
                List.of(capacityKey(nodeId), leaseKey(sessionId)),
                nodeId
        );
    }

    private String capacityKey(String nodeId) {
        return "node:" + nodeId + ":available_slots";
    }

    private String leaseKey(String sessionId) {
        return "session:" + sessionId + ":lease";
    }
}
