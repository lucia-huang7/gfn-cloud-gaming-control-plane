package com.gfn.controlplane.state;

public record IdempotencyClaim(
        boolean claimed,
        String sessionId
) {
}

