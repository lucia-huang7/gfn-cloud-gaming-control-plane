package com.gfn.controlplane.session;

import com.gfn.controlplane.persistence.SessionEvent;
import com.gfn.controlplane.persistence.SessionEventRepository;
import com.gfn.controlplane.placement.PlacementResult;
import com.gfn.controlplane.placement.PlacementService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {
    private final PlacementService placementService;
    private final SessionEventRepository eventRepository;
    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();

    public SessionService(PlacementService placementService, SessionEventRepository eventRepository) {
        this.placementService = placementService;
        this.eventRepository = eventRepository;
    }

    public SessionResponse createSession(String idempotencyKey, CreateSessionRequest request) {
        String existingSessionId = idempotencyKeys.get(idempotencyKey);
        if (existingSessionId != null) {
            return toResponse(sessions.get(existingSessionId));
        }

        String sessionId = "sess_" + UUID.randomUUID();
        SessionRecord session = new SessionRecord(sessionId, request.userId(), request.gameId(), request.region(), request.gpuProfile());
        sessions.put(sessionId, session);
        idempotencyKeys.put(idempotencyKey, sessionId);

        PlacementResult placement = placementService.place(sessionId, request);
        if (placement.reserved()) {
            session.status(SessionStatus.RESERVED);
            session.nodeId(placement.nodeId());
            saveEvent(session, "PLACEMENT_RESERVED");
        } else {
            session.status(SessionStatus.QUEUED);
            saveEvent(session, "SESSION_QUEUED");
        }

        return toResponse(session);
    }

    public SessionResponse getSession(String sessionId) {
        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        return toResponse(session);
    }

    public void terminateSession(String sessionId) {
        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        if (session.nodeId() != null) {
            placementService.release(session.nodeId(), session.sessionId());
        }
        session.status(SessionStatus.TERMINATED);
        saveEvent(session, "SESSION_TERMINATED");
    }

    public List<SessionRecord> queuedSessions() {
        return sessions.values().stream()
                .filter(session -> session.status() == SessionStatus.QUEUED)
                .sorted(Comparator.comparing(SessionRecord::createdAt))
                .toList();
    }

    public List<SessionRecord> activeReservations() {
        return sessions.values().stream()
                .filter(session -> session.status() == SessionStatus.RESERVED)
                .toList();
    }

    public void expire(SessionRecord session) {
        if (session.nodeId() != null) {
            placementService.release(session.nodeId(), session.sessionId());
        }
        session.status(SessionStatus.EXPIRED);
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
}

