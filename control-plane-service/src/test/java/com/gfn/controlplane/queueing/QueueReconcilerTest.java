package com.gfn.controlplane.queueing;

import com.gfn.controlplane.node.NodeService;
import com.gfn.controlplane.session.SessionRecord;
import com.gfn.controlplane.session.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueReconcilerTest {
    @Test
    void reconcileMarksStaleNodesExpiresReservationsAndDrainsQueue() {
        SessionService sessionService = mock(SessionService.class);
        NodeService nodeService = mock(NodeService.class);
        QueueReconciler reconciler = new QueueReconciler(sessionService, nodeService, 20, 45);

        when(sessionService.activeReservations()).thenReturn(List.<SessionRecord>of());

        reconciler.reconcile();

        verify(nodeService).markStaleNodes(any());
        verify(sessionService).activeReservations();
        verify(sessionService).drainQueuedSessions();
    }

    @Test
    void doesNotExpireRecentlyReservedSessionThatWasQueuedForALongTime() {
        SessionService sessionService = mock(SessionService.class);
        NodeService nodeService = mock(NodeService.class);
        QueueReconciler reconciler = new QueueReconciler(sessionService, nodeService, 20, 45);
        SessionRecord session = reservedSession(
                Instant.now().minusSeconds(600),
                Instant.now()
        );
        when(sessionService.activeReservations()).thenReturn(List.of(session));

        reconciler.reconcile();

        verify(sessionService, never()).expire(session);
    }

    @Test
    void expiresSessionWhenReservationTimestampIsOlderThanTtl() {
        SessionService sessionService = mock(SessionService.class);
        NodeService nodeService = mock(NodeService.class);
        QueueReconciler reconciler = new QueueReconciler(sessionService, nodeService, 20, 45);
        SessionRecord session = reservedSession(
                Instant.now(),
                Instant.now().minusSeconds(60)
        );
        when(sessionService.activeReservations()).thenReturn(List.of(session));

        reconciler.reconcile();

        verify(sessionService).expire(session);
    }

    private SessionRecord reservedSession(Instant createdAt, Instant reservedAt) {
        SessionRecord session = new SessionRecord(
                "sess-1",
                "tenant-a",
                "user_123",
                "cyberpunk2077",
                com.gfn.controlplane.session.Region.US_WEST,
                com.gfn.controlplane.session.GpuProfile.ULTRA,
                45,
                createdAt
        );
        session.status(com.gfn.controlplane.session.SessionStatus.RESERVED);
        session.nodeId("node-1");
        session.reservedAt(reservedAt);
        return session;
    }
}
