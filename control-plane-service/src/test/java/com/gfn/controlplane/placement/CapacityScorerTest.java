package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import com.gfn.controlplane.node.RegisterNodeRequest;
import com.gfn.controlplane.session.CreateSessionRequest;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityScorerTest {
    private final CapacityScorer scorer = new CapacityScorer();

    @Test
    void prefersLowerLatencyWhenCapacityIsComparable() {
        CreateSessionRequest request = new CreateSessionRequest("u1", "game", Region.US_WEST, GpuProfile.ULTRA, 60);
        GpuNode fast = new GpuNode(new RegisterNodeRequest("fast", Region.US_WEST, GpuProfile.ULTRA, 4, 20));
        GpuNode slow = new GpuNode(new RegisterNodeRequest("slow", Region.US_WEST, GpuProfile.ULTRA, 4, 55));

        assertThat(scorer.score(fast, request)).isGreaterThan(scorer.score(slow, request));
    }
}

