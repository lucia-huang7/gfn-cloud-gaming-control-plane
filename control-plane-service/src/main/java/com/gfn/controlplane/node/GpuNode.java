package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class GpuNode {
    private final String nodeId;
    private final Region region;
    private final GpuProfile gpuProfile;
    private final int totalSlots;
    private final int avgLatencyMs;
    private final AtomicInteger availableSlots;
    private final AtomicInteger activeSessions;
    private volatile Instant lastHeartbeatAt;
    private volatile NodeStatus status;

    public GpuNode(RegisterNodeRequest request) {
        this.nodeId = request.nodeId();
        this.region = request.region();
        this.gpuProfile = request.gpuProfile();
        this.totalSlots = request.totalSlots();
        this.avgLatencyMs = request.avgLatencyMs();
        this.availableSlots = new AtomicInteger(request.totalSlots());
        this.activeSessions = new AtomicInteger(0);
        this.lastHeartbeatAt = Instant.now();
        this.status = NodeStatus.HEALTHY;
    }

    public String nodeId() {
        return nodeId;
    }

    public Region region() {
        return region;
    }

    public GpuProfile gpuProfile() {
        return gpuProfile;
    }

    public int totalSlots() {
        return totalSlots;
    }

    public int availableSlots() {
        return availableSlots.get();
    }

    public int activeSessions() {
        return activeSessions.get();
    }

    public int avgLatencyMs() {
        return avgLatencyMs;
    }

    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public NodeStatus status() {
        return status;
    }

    public void heartbeat(HeartbeatRequest request) {
        availableSlots.set(Math.min(request.availableSlots(), totalSlots));
        activeSessions.set(Math.min(request.activeSessions(), totalSlots));
        lastHeartbeatAt = Instant.now();
        status = NodeStatus.HEALTHY;
    }

    public boolean tryReserveLocalSlot() {
        while (true) {
            int current = availableSlots.get();
            if (current <= 0) {
                return false;
            }
            if (availableSlots.compareAndSet(current, current - 1)) {
                activeSessions.incrementAndGet();
                return true;
            }
        }
    }

    public void releaseLocalSlot() {
        availableSlots.updateAndGet(current -> Math.min(totalSlots, current + 1));
        activeSessions.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void markStale() {
        status = NodeStatus.STALE;
    }
}

