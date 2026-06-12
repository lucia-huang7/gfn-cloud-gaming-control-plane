package com.gfn.controlplane.node;

import jakarta.validation.constraints.Min;

public record HeartbeatRequest(
        @Min(0) int availableSlots,
        @Min(0) int activeSessions
) {
}

