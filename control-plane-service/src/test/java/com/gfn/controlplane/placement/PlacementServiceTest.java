package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.NodeService;
import com.gfn.controlplane.session.CreateSessionRequest;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacementServiceTest {
    @Test
    void queuesWhenNoNodeSatisfiesLatencySla() {
        NodeService nodeService = mock(NodeService.class);
        CapacityScorer scorer = new CapacityScorer();
        RedisLeaseManager leaseManager = mock(RedisLeaseManager.class);
        PlacementService placementService = new PlacementService(nodeService, scorer, leaseManager);
        CreateSessionRequest request = new CreateSessionRequest(
                "user_123",
                "cyberpunk2077",
                Region.US_WEST,
                GpuProfile.ULTRA,
                45
        );

        when(nodeService.findHealthyNodes(Region.US_WEST, GpuProfile.ULTRA, 45)).thenReturn(List.of());

        PlacementResult result = placementService.place("sess_1", request);

        assertThat(result.reserved()).isFalse();
        assertThat(result.reason()).contains("no healthy capacity");
        verify(leaseManager, never()).tryReserve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}

