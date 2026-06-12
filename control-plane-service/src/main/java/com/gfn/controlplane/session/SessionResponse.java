package com.gfn.controlplane.session;

import java.time.Instant;

public record SessionResponse(
        String sessionId,
        SessionStatus status,
        Region region,
        GpuProfile gpuProfile,
        String nodeId,
        Integer queuePosition,
        Integer estimatedWaitSeconds,
        Instant createdAt
) {
}

