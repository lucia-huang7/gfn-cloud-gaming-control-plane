package com.gfn.controlplane.queueing;

import com.gfn.controlplane.node.NodeService;
import com.gfn.controlplane.session.SessionRecord;
import com.gfn.controlplane.session.SessionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}

