package com.gfn.controlplane.state;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import com.gfn.controlplane.session.SessionStatus;

import java.time.Instant;

public record SessionSnapshot(
        String sessionId,
        String userId,
        String gameId,
        Region region,
        GpuProfile gpuProfile,
        int maxLatencyMs,
        Instant createdAt,
        SessionStatus status,
        String nodeId
) {
}
