package com.gfn.controlplane.state;

import com.gfn.controlplane.node.NodeStatus;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;

import java.time.Instant;

public record NodeSnapshot(
        String nodeId,
        Region region,
        GpuProfile gpuProfile,
        int totalSlots,
        int availableSlots,
        int activeSessions,
        int avgLatencyMs,
        Instant lastHeartbeatAt,
        NodeStatus status
) {
}

