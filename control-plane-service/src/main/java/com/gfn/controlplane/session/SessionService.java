package com.gfn.controlplane.session;

import com.gfn.controlplane.persistence.SessionEvent;
import com.gfn.controlplane.persistence.SessionEventRepository;
import com.gfn.controlplane.placement.PlacementResult;
import com.gfn.controlplane.placement.PlacementService;
import com.gfn.controlplane.state.RedisStateStore;
import com.gfn.controlplane.state.SessionSnapshot;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {
    private final PlacementService placementService;
    private final SessionEventRepository eventRepository;
    private final RedisStateStore stateStore;

    public SessionService(
            PlacementService placementService,
            SessionEventRepository eventRepository,
            RedisStateStore stateStore) {
        this.placementService = placementService;
        this.eventRepository = eventRepository;
        this.stateStore = stateStore;
    }

    public SessionResponse createSession(String idempotencyKey, CreateSessionRequest request) {
        Optional<SessionResponse> existing = stateStore.getSessionIdForIdempotencyKey(idempotencyKey)
                .flatMap(stateStore::findSession)
                .map(this::fromSnapshot)
                .map(this::toResponse);
        if (existing.isPresent()) {
            return existing.get();
        }

        String sessionId = "sess_" + UUID.randomUUID();
        if (!stateStore.putIdempotencyKeyIfAbsent(idempotencyKey, sessionId)) {
            return stateStore.getSessionIdForIdempotencyKey(idempotencyKey)
                    .flatMap(stateStore::findSession)
                    .map(this::fromSnapshot)
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key exists without a session record"));
        }

        SessionRecord session = new SessionRecord(sessionId, request.userId(), request.gameId(), request.region(), request.gpuProfile());
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
        try {
            eventRepository.save(SessionEvent.from(session, type));
        } catch (RuntimeException ignored) {
            // Cassandra is optional for local API exploration; Docker Compose enables persistence.
        }
    }

    private SessionSnapshot toSnapshot(SessionRecord session) {
        return new SessionSnapshot(
                session.sessionId(),
                session.userId(),
                session.gameId(),
                session.region(),
                session.gpuProfile(),
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
                snapshot.createdAt()
        );
        session.status(snapshot.status());
        session.nodeId(snapshot.nodeId());
        return session;
    }

}
