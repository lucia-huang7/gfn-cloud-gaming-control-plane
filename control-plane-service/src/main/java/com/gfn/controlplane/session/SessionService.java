package com.gfn.controlplane.session;

import com.gfn.controlplane.events.SessionEventPublisher;
import com.gfn.controlplane.placement.PlacementResult;
import com.gfn.controlplane.placement.PlacementService;
import com.gfn.controlplane.state.IdempotencyClaim;
import com.gfn.controlplane.state.RedisStateStore;
import com.gfn.controlplane.state.SessionSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class SessionService {
    private static final Duration IDEMPOTENCY_CLAIM_TTL = Duration.ofMinutes(10);
    private static final int IDEMPOTENCY_WAIT_ATTEMPTS = 20;
    private static final long IDEMPOTENCY_WAIT_MS = 25;

    private final PlacementService placementService;
    private final SessionEventPublisher eventPublisher;
    private final RedisStateStore stateStore;
    private final Duration idempotencyClaimTtl;

    public SessionService(
            PlacementService placementService,
            SessionEventPublisher eventPublisher,
            RedisStateStore stateStore,
            @Value("${control-plane.idempotency-claim-ttl-seconds:600}") long idempotencyClaimTtlSeconds) {
        this.placementService = placementService;
        this.eventPublisher = eventPublisher;
        this.stateStore = stateStore;
        this.idempotencyClaimTtl = Duration.ofSeconds(idempotencyClaimTtlSeconds);
    }

    public SessionResponse createSession(String idempotencyKey, CreateSessionRequest request) {
        String sessionId = "sess_" + UUID.randomUUID();
        IdempotencyClaim claim = stateStore.claimIdempotencyKey(idempotencyKey, sessionId, idempotencyClaimTtl);
        if (!claim.claimed()) {
            return waitForClaimedSession(claim.sessionId());
        }

        SessionRecord session = new SessionRecord(
                sessionId,
                request.userId(),
                request.gameId(),
                request.region(),
                request.gpuProfile(),
                request.maxLatencyMs()
        );
        stateStore.saveSession(toSnapshot(session));

        PlacementResult placement = placementService.place(sessionId, request);
        if (placement.reserved()) {
            session.status(SessionStatus.RESERVED);
            session.nodeId(placement.nodeId());
            saveEvent(session, "PLACEMENT_RESERVED");
        } else {
            session.status(SessionStatus.QUEUED);
            saveEvent(session, "SESSION_QUEUED");
        }
        stateStore.saveSession(toSnapshot(session));

        return toResponse(session);
    }

    private SessionResponse waitForClaimedSession(String sessionId) {
        for (int attempt = 0; attempt < IDEMPOTENCY_WAIT_ATTEMPTS; attempt++) {
            SessionResponse response = stateStore.findSession(sessionId)
                    .map(this::fromSnapshot)
                    .map(this::toResponse)
                    .orElse(null);
            if (response != null) {
                return response;
            }
            sleepBeforeRetry();
        }
        throw new IllegalStateException("Idempotency key is claimed but session is not visible yet: " + sessionId);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(IDEMPOTENCY_WAIT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for idempotent session", ex);
        }
    }

    public SessionResponse getSession(String sessionId) {
        SessionRecord session = stateStore.findSession(sessionId)
                .map(this::fromSnapshot)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
        return toResponse(session);
    }

    public void terminateSession(String sessionId) {
        SessionRecord session = stateStore.findSession(sessionId).map(this::fromSnapshot).orElse(null);
        if (session == null) return;
        if (session.status() == SessionStatus.TERMINATED || session.status() == SessionStatus.EXPIRED) {
            return;
        }
        if (session.nodeId() != null) {
            placementService.release(session.nodeId(), session.sessionId());
        }
        session.status(SessionStatus.TERMINATED);
        stateStore.saveSession(toSnapshot(session));
        saveEvent(session, "SESSION_TERMINATED");
    }

    public List<SessionRecord> queuedSessions() {
        return stateStore.listSessions().stream()
                .map(this::fromSnapshot)
                .filter(session -> session.status() == SessionStatus.QUEUED)
                .sorted(Comparator.comparing(SessionRecord::createdAt))
                .toList();
    }

    public List<SessionRecord> activeReservations() {
        return stateStore.listSessions().stream()
                .map(this::fromSnapshot)
                .filter(session -> session.status() == SessionStatus.RESERVED)
                .toList();
    }

    public int drainQueuedSessions() {
        int placed = 0;
        for (SessionRecord session : queuedSessions()) {
            PlacementResult placement = placementService.place(session.sessionId(), toPlacementRequest(session));
            if (!placement.reserved()) {
                break;
            }
            session.status(SessionStatus.RESERVED);
            session.nodeId(placement.nodeId());
            stateStore.saveSession(toSnapshot(session));
            saveEvent(session, "QUEUE_PLACEMENT_RESERVED");
            placed++;
        }
        return placed;
    }

    public void expire(SessionRecord session) {
        if (session.nodeId() != null) {
            placementService.release(session.nodeId(), session.sessionId());
        }
        session.status(SessionStatus.EXPIRED);
        stateStore.saveSession(toSnapshot(session));
        saveEvent(session, "SESSION_EXPIRED");
    }

    private SessionResponse toResponse(SessionRecord session) {
        int queuePosition = -1;
        if (session.status() == SessionStatus.QUEUED) {
            List<SessionRecord> queued = queuedSessions();
            queuePosition = queued.indexOf(session) + 1;
        }
        Integer position = queuePosition > 0 ? queuePosition : null;
        Integer waitSeconds = position == null ? null : position * 15;
        return session.toResponse(position, waitSeconds);
    }

    private void saveEvent(SessionRecord session, String type) {
        eventPublisher.publish(session, type);
    }

    private SessionSnapshot toSnapshot(SessionRecord session) {
        return new SessionSnapshot(
                session.sessionId(),
                session.userId(),
                session.gameId(),
                session.region(),
                session.gpuProfile(),
                session.maxLatencyMs(),
                session.createdAt(),
                session.status(),
                session.nodeId()
        );
    }

    private SessionRecord fromSnapshot(SessionSnapshot snapshot) {
        SessionRecord session = new SessionRecord(
                snapshot.sessionId(),
                snapshot.userId(),
                snapshot.gameId(),
                snapshot.region(),
                snapshot.gpuProfile(),
                snapshot.maxLatencyMs(),
                snapshot.createdAt()
        );
        session.status(snapshot.status());
        session.nodeId(snapshot.nodeId());
        return session;
    }

    private CreateSessionRequest toPlacementRequest(SessionRecord session) {
        return new CreateSessionRequest(
                session.userId(),
                session.gameId(),
                session.region(),
                session.gpuProfile(),
                session.maxLatencyMs()
        );
    }

}
