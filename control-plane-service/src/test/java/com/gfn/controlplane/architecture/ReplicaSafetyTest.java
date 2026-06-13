package com.gfn.controlplane.architecture;

import com.gfn.controlplane.node.NodeService;
import com.gfn.controlplane.session.SessionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaSafetyTest {
    @Test
    void servicesDoNotHoldAuthoritativeLocalMaps() {
        assertThat(hasMapField(SessionService.class)).isFalse();
        assertThat(hasMapField(NodeService.class)).isFalse();
    }

    private boolean hasMapField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (Map.class.isAssignableFrom(fieldType) || ConcurrentHashMap.class.isAssignableFrom(fieldType)) {
                return true;
            }
        }
        return false;
    }
}

