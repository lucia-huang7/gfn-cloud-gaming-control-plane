package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;

import java.time.Instant;

public record NodeResponse(
        String nodeId,
        Region region,
        GpuProfile gpuProfile,
        int totalSlots,
        int availableSlots,
        int activeSessions,
        int avgLatencyMs,
        NodeStatus status,
        Instant lastHeartbeatAt
) {
    public static NodeResponse from(GpuNode node) {
        return new NodeResponse(
                node.nodeId(),
                node.region(),
                node.gpuProfile(),
                node.totalSlots(),
                node.availableSlots(),
                node.activeSessions(),
                node.avgLatencyMs(),
                node.status(),
                node.lastHeartbeatAt()
        );
    }
}

