package com.gfn.controlplane.session;

import com.gfn.controlplane.events.SessionEventPublisher;
import com.gfn.controlplane.placement.PlacementResult;
import com.gfn.controlplane.placement.PlacementService;
import com.gfn.controlplane.security.CallerContext;
import com.gfn.controlplane.security.CallerRole;
import com.gfn.controlplane.state.IdempotencyClaim;
import com.gfn.controlplane.state.RedisStateStore;
import com.gfn.controlplane.state.SessionSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {
    private final PlacementService placementService = mock(PlacementService.class);
    private final SessionEventPublisher eventPublisher = mock(SessionEventPublisher.class);
    private final RedisStateStore stateStore = mock(RedisStateStore.class);
    private final SessionService service = new SessionService(placementService, eventPublisher, stateStore, 600);

    @AfterEach
    void clearCaller() {
        CallerContext.clear();
    }

    @Test
    void idempotencyLoserWaitsForExistingSessionAndDoesNotPlaceAgain() {
        CallerContext.set(new CallerContext(CallerRole.CLIENT, "tenant-a"));
        SessionSnapshot existing = reservedSnapshot("sess-existing", "tenant-a");
        when(stateStore.claimIdempotencyKey(eq("tenant-a:idem-1"), any(String.class), any(Duration.class)))
                .thenReturn(new IdempotencyClaim(false, "sess-existing"));
        when(stateStore.findSession("sess-existing")).thenReturn(Optional.of(existing));

        SessionResponse response = service.createSession("idem-1", request());

        assertThat(response.sessionId()).isEqualTo("sess-existing");
        verify(placementService, never()).place(any(String.class), any(CreateSessionRequest.class));
    }

    @Test
    void rejectsCrossTenantSessionRead() {
        CallerContext.set(new CallerContext(CallerRole.CLIENT, "tenant-b"));
        when(stateStore.findSession("sess-a")).thenReturn(Optional.of(reservedSnapshot("sess-a", "tenant-a")));

        assertThatThrownBy(() -> service.getSession("sess-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown session");
    }

    @Test
    void drainsQueuedSessionsInFifoOrder() {
        SessionSnapshot queued = new SessionSnapshot(
                "sess-q",
                "tenant-a",
                "user_123",
                "cyberpunk2077",
                Region.US_WEST,
                GpuProfile.ULTRA,
                45,
                Instant.now(),
                SessionStatus.QUEUED,
                null
        );
        when(stateStore.listSessions()).thenReturn(List.of(queued));
        when(placementService.place(eq("sess-q"), any(CreateSessionRequest.class)))
                .thenReturn(PlacementResult.reserved("sess-q", "node-1"));

        int drained = service.drainQueuedSessions();

        assertThat(drained).isEqualTo(1);
        ArgumentCaptor<SessionSnapshot> saved = ArgumentCaptor.forClass(SessionSnapshot.class);
        verify(stateStore).saveSession(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(SessionStatus.RESERVED);
        assertThat(saved.getValue().nodeId()).isEqualTo("node-1");
    }

    private CreateSessionRequest request() {
        return new CreateSessionRequest("user_123", "cyberpunk2077", Region.US_WEST, GpuProfile.ULTRA, 45);
    }

    private SessionSnapshot reservedSnapshot(String sessionId, String tenantId) {
        return new SessionSnapshot(
                sessionId,
                tenantId,
                "user_123",
                "cyberpunk2077",
                Region.US_WEST,
                GpuProfile.ULTRA,
                45,
                Instant.now(),
                SessionStatus.RESERVED,
                "node-1"
        );
    }
}

