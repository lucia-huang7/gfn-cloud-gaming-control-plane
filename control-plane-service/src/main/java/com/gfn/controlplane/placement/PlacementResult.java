package com.gfn.controlplane.placement;

public record PlacementResult(
        boolean reserved,
        String sessionId,
        String nodeId,
        String reason
) {
    public static PlacementResult reserved(String sessionId, String nodeId) {
        return new PlacementResult(true, sessionId, nodeId, "reserved");
    }

    public static PlacementResult queued(String sessionId) {
        return new PlacementResult(false, sessionId, null, "no healthy capacity available");
    }
}

