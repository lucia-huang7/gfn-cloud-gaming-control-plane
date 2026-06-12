package com.gfn.controlplane.placement;

import com.gfn.controlplane.node.GpuNode;
import com.gfn.controlplane.session.CreateSessionRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class CapacityScorer {
    public double score(GpuNode node, CreateSessionRequest request) {
        double capacityScore = (double) node.availableSlots() / Math.max(1, node.totalSlots());
        double latencyScore = Math.max(0, request.maxLatencyMs() - node.avgLatencyMs()) / (double) request.maxLatencyMs();
        double freshnessScore = heartbeatFreshness(node);
        double loadPenalty = (double) node.activeSessions() / Math.max(1, node.totalSlots());

        return capacityScore * 0.45
                + latencyScore * 0.30
                + freshnessScore * 0.20
                - loadPenalty * 0.15;
    }

    private double heartbeatFreshness(GpuNode node) {
        long ageSeconds = Duration.between(node.lastHeartbeatAt(), Instant.now()).toSeconds();
        if (ageSeconds <= 5) {
            return 1.0;
        }
        if (ageSeconds >= 30) {
            return 0.0;
        }
        return 1.0 - ((ageSeconds - 5) / 25.0);
    }
}

