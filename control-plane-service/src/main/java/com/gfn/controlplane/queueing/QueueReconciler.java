package com.gfn.controlplane.queueing;

import com.gfn.controlplane.node.NodeService;
import com.gfn.controlplane.session.SessionRecord;
import com.gfn.controlplane.session.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class QueueReconciler {
    private final SessionService sessionService;
    private final NodeService nodeService;
    private final Duration heartbeatTimeout;
    private final Duration reservationTtl;

    public QueueReconciler(
            SessionService sessionService,
            NodeService nodeService,
            @Value("${control-plane.heartbeat-timeout-seconds:20}") long heartbeatTimeoutSeconds,
            @Value("${control-plane.reservation-ttl-seconds:45}") long reservationTtlSeconds) {
        this.sessionService = sessionService;
        this.nodeService = nodeService;
        this.heartbeatTimeout = Duration.ofSeconds(heartbeatTimeoutSeconds);
        this.reservationTtl = Duration.ofSeconds(reservationTtlSeconds);
    }

    @Scheduled(fixedDelayString = "${control-plane.reconcile-interval-ms:5000}")
    public void reconcile() {
        nodeService.markStaleNodes(heartbeatTimeout);
        Instant cutoff = Instant.now().minus(reservationTtl);
        for (SessionRecord session : sessionService.activeReservations()) {
            if (session.createdAt().isBefore(cutoff)) {
                sessionService.expire(session);
            }
        }
    }
}

