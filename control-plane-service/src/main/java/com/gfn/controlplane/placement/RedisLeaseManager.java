package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RedisLeaseManager {
    private static final String LUA_RESERVE = """
            local capacityKey = KEYS[1]
            local leaseKey = KEYS[2]
            local ttlSeconds = tonumber(ARGV[1])
            local capacity = tonumber(redis.call('GET', capacityKey) or '-1')
            if capacity < 0 then
              redis.call('SET', capacityKey, ARGV[2])
              capacity = tonumber(ARGV[2])
            end
            if capacity <= 0 then
              return 0
            end
            redis.call('DECR', capacityKey)
            redis.call('SET', leaseKey, ARGV[3], 'EX', ttlSeconds)
            return 1
            """;

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;
    private final ConcurrentHashMap<String, String> localLeases = new ConcurrentHashMap<>();

    public RedisLeaseManager(
            StringRedisTemplate redisTemplate,
            @Value("${control-plane.reservation-ttl-seconds:45}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    public boolean tryReserve(GpuNode node, String sessionId) {
        try {
            Long reserved = redisTemplate.execute(
                    new DefaultRedisScript<>(LUA_RESERVE, Long.class),
                    List.of(capacityKey(node.nodeId()), leaseKey(sessionId)),
                    String.valueOf(ttlSeconds),
                    String.valueOf(node.availableSlots()),
                    node.nodeId()
            );
            if (Long.valueOf(1L).equals(reserved)) {
                return node.tryReserveLocalSlot();
            }
            return false;
        } catch (RedisConnectionFailureException ex) {
            boolean reserved = node.tryReserveLocalSlot();
            if (reserved) {
                localLeases.put(sessionId, node.nodeId());
            }
            return reserved;
        }
    }

    public void release(GpuNode node, String sessionId) {
        node.releaseLocalSlot();
        localLeases.remove(sessionId);
        try {
            redisTemplate.delete(leaseKey(sessionId));
            redisTemplate.opsForValue().increment(capacityKey(node.nodeId()));
        } catch (RedisConnectionFailureException ignored) {
            // In local fallback mode the in-memory node count is the source of truth.
        }
    }

    private String capacityKey(String nodeId) {
        return "node:" + nodeId + ":available_slots";
    }

    private String leaseKey(String sessionId) {
        return "session:" + sessionId + ":lease";
    }
}
