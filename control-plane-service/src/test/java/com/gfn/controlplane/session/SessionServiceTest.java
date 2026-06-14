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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionServiceTest {
    private final PlacementService placementService = mock(PlacementService.class);
    private final SessionEventPublisher eventPublisher = mock(SessionEventPublisher.class);
    private final RedisStateStore stateStore = mock(RedisStateStore.class);
    private final SessionService service = new SessionService(placementService, eventPublisher, stateStore, 600, 30);

    @AfterEach
    void clearCaller() {
        CallerContext.clear();
    }

    @Test
    void idempotencyLoserWaitsForExistingSessionAndDoesNotPlaceAgain() {
        CallerContext.set(new CallerContext(CallerRole.CLIENT, "tenant-a"));
        SessionSnapshot existing = reservedSnapshot("sess-existing", "tenant-a");
        when(stateStore.claimIdempotencyKey(eq("tenant-a:idem-1"), any(String.class), any(String.class), any(Duration.class)))
                .thenAnswer(invocation -> new IdempotencyClaim(false, "sess-existing", invocation.getArgument(2)));
        when(stateStore.findSession("sess-existing")).thenReturn(Optional.of(existing));

        SessionResponse response = service.createSession("idem-1", request());

        assertThat(response.sessionId()).isEqualTo("sess-existing");
        verify(placementService, never()).place(any(String.class), any(CreateSessionRequest.class));
    }

    @Test
    void rejectsIdempotencyKeyReusedWithDifferentRequestBody() {
        CallerContext.set(new CallerContext(CallerRole.CLIENT, "tenant-a"));
        when(stateStore.claimIdempotencyKey(eq("tenant-a:idem-1"), any(String.class), any(String.class), any(Duration.class)))
                .thenReturn(new IdempotencyClaim(false, "sess-existing", "different-fingerprint"));

        assertThatThrownBy(() -> service.createSession("idem-1", request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different request body");
        verify(placementService, never()).place(any(String.class), any(CreateSessionRequest.class));
        verify(stateStore, never()).findSession("sess-existing");
    }

    @Test
    void releasesIdempotencyClaimWhenSessionIsNotSaved() {
        CallerContext.set(new CallerContext(CallerRole.CLIENT, "tenant-a"));
        when(stateStore.claimIdempotencyKey(eq("tenant-a:idem-1"), any(String.class), any(String.class), any(Duration.class)))
                .thenAnswer(invocation -> new IdempotencyClaim(true, invocation.getArgument(1), invocation.getArgument(2)));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stateStore).saveSession(any(SessionSnapshot.class));

        assertThatThrownBy(() -> service.createSession("idem-1", request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis unavailable");
        verify(stateStore).releaseIdempotencyClaim(eq("tenant-a:idem-1"), any(String.class), any(String.class));
    }

    @Test
    void marksSessionFailedWhenPlacementFailsAfterSessionIsVisible() {
        CallerContext.set(new CallerContext(CallerRole.CLIENT, "tenant-a"));
        when(stateStore.claimIdempotencyKey(eq("tenant-a:idem-1"), any(String.class), any(String.class), any(Duration.class)))
                .thenAnswer(invocation -> new IdempotencyClaim(true, invocation.getArgument(1), invocation.getArgument(2)));
        when(placementService.place(any(String.class), any(CreateSessionRequest.class)))
                .thenThrow(new IllegalStateException("placement failed"));

        assertThatThrownBy(() -> service.createSession("idem-1", request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placement failed");
        verify(stateStore, never()).releaseIdempotencyClaim(eq("tenant-a:idem-1"), any(String.class), any(String.class));
        ArgumentCaptor<SessionSnapshot> saved = ArgumentCaptor.forClass(SessionSnapshot.class);
        verify(stateStore, org.mockito.Mockito.times(2)).saveSession(saved.capture());
        assertThat(saved.getAllValues().get(0).status()).isEqualTo(SessionStatus.QUEUED);
        assertThat(saved.getAllValues().get(1).status()).isEqualTo(SessionStatus.FAILED);
        verify(eventPublisher).publish(any(SessionRecord.class), eq("PLACEMENT_FAILED"));
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
                null,
                null
        );
        when(stateStore.listSessions()).thenReturn(List.of(queued));
        when(stateStore.claimQueuedSession(eq("sess-q"), eq(Duration.ofSeconds(30)))).thenReturn(Optional.of("claim-token"));
        when(stateStore.findSession("sess-q")).thenReturn(Optional.of(queued));
        when(placementService.place(eq("sess-q"), any(CreateSessionRequest.class)))
                .thenReturn(PlacementResult.reserved("sess-q", "node-1"));

        int drained = service.drainQueuedSessions();

        assertThat(drained).isEqualTo(1);
        ArgumentCaptor<SessionSnapshot> saved = ArgumentCaptor.forClass(SessionSnapshot.class);
        verify(stateStore).saveSession(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(SessionStatus.RESERVED);
        assertThat(saved.getValue().nodeId()).isEqualTo("node-1");
        assertThat(saved.getValue().reservedAt()).isNotNull();
        verify(stateStore).releaseQueuedSessionClaim("sess-q", "claim-token");
    }

    @Test
    void skipsQueuedSessionClaimedByAnotherReplica() {
        SessionSnapshot queued = queuedSnapshot("sess-q");
        when(stateStore.listSessions()).thenReturn(List.of(queued));
        when(stateStore.claimQueuedSession(eq("sess-q"), eq(Duration.ofSeconds(30)))).thenReturn(Optional.empty());

        int drained = service.drainQueuedSessions();

        assertThat(drained).isZero();
        verifyNoInteractions(placementService);
        verify(stateStore, never()).saveSession(any(SessionSnapshot.class));
    }

    @Test
    void reReadsQueuedSessionAfterClaimBeforePlacement() {
        SessionSnapshot queued = queuedSnapshot("sess-q");
        SessionSnapshot alreadyReserved = reservedSnapshot("sess-q", "tenant-a");
        when(stateStore.listSessions()).thenReturn(List.of(queued));
        when(stateStore.claimQueuedSession(eq("sess-q"), eq(Duration.ofSeconds(30)))).thenReturn(Optional.of("claim-token"));
        when(stateStore.findSession("sess-q")).thenReturn(Optional.of(alreadyReserved));

        int drained = service.drainQueuedSessions();

        assertThat(drained).isZero();
        verifyNoInteractions(placementService);
        verify(stateStore).releaseQueuedSessionClaim("sess-q", "claim-token");
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
                "node-1",
                Instant.now()
        );
    }

    private SessionSnapshot queuedSnapshot(String sessionId) {
        return new SessionSnapshot(
                sessionId,
                "tenant-a",
                "user_123",
                "cyberpunk2077",
                Region.US_WEST,
                GpuProfile.ULTRA,
                45,
                Instant.now(),
                SessionStatus.QUEUED,
                null,
                null
        );
    }
}
