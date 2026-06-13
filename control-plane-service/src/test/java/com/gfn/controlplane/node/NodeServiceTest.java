package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import com.gfn.controlplane.state.NodeSnapshot;
import com.gfn.controlplane.state.RedisStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeServiceTest {
    private final RedisStateStore stateStore = mock(RedisStateStore.class);
    private final NodeService nodeService = new NodeService(stateStore);

    @Test
    void registerInitializesControlPlaneCapacityCounter() {
        RegisterNodeRequest request = new RegisterNodeRequest("node-1", Region.US_WEST, GpuProfile.ULTRA, 8, 20);

        NodeResponse response = nodeService.register(request);

        assertThat(response.availableSlots()).isEqualTo(8);
        verify(stateStore).saveNode(any(NodeSnapshot.class));
        verify(stateStore).setNodeAvailableSlots("node-1", 8);
    }

    @Test
    void heartbeatDoesNotOverwriteControlPlaneCapacityCounter() {
        when(stateStore.findNode("node-1")).thenReturn(Optional.of(node("node-1", 8, 8, 0)));
        when(stateStore.getNodeAvailableSlots("node-1")).thenReturn(Optional.of(7));

        NodeResponse response = nodeService.heartbeat("node-1", new HeartbeatRequest(8, 0));

        assertThat(response.availableSlots()).isEqualTo(7);
        assertThat(response.activeSessions()).isEqualTo(1);
        verify(stateStore).saveNode(any(NodeSnapshot.class));
        verify(stateStore, never()).setNodeAvailableSlots("node-1", 8);
    }

    private NodeSnapshot node(String nodeId, int totalSlots, int availableSlots, int activeSessions) {
        return new NodeSnapshot(
                nodeId,
                Region.US_WEST,
                GpuProfile.ULTRA,
                totalSlots,
                availableSlots,
                activeSessions,
                20,
                Instant.parse("2026-06-13T00:00:00Z"),
                NodeStatus.HEALTHY
        );
    }
}
