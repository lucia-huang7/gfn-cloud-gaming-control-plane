package com.gfn.controlplane.session;

import java.time.Instant;

public class SessionRecord {
    private final String sessionId;
    private final String tenantId;
    private final String userId;
    private final String gameId;
    private final Region region;
    private final GpuProfile gpuProfile;
    private final int maxLatencyMs;
    private final Instant createdAt;
    private volatile SessionStatus status;
    private volatile String nodeId;

    public SessionRecord(String sessionId, String tenantId, String userId, String gameId, Region region, GpuProfile gpuProfile, int maxLatencyMs) {
        this(sessionId, tenantId, userId, gameId, region, gpuProfile, maxLatencyMs, Instant.now());
    }

    public SessionRecord(String sessionId, String tenantId, String userId, String gameId, Region region, GpuProfile gpuProfile, int maxLatencyMs, Instant createdAt) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.gameId = gameId;
        this.region = region;
        this.gpuProfile = gpuProfile;
        this.maxLatencyMs = maxLatencyMs;
        this.createdAt = createdAt;
        this.status = SessionStatus.QUEUED;
    }

    public String sessionId() {
        return sessionId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String userId() {
        return userId;
    }

    public String gameId() {
        return gameId;
    }

    public Region region() {
        return region;
    }

    public GpuProfile gpuProfile() {
        return gpuProfile;
    }

    public int maxLatencyMs() {
        return maxLatencyMs;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public SessionStatus status() {
        return status;
    }

    public void status(SessionStatus status) {
        this.status = status;
    }

    public String nodeId() {
        return nodeId;
    }

    public void nodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public SessionResponse toResponse(Integer queuePosition, Integer estimatedWaitSeconds) {
        return new SessionResponse(sessionId, tenantId, status, region, gpuProfile, nodeId, queuePosition, estimatedWaitSeconds, createdAt);
    }
}
