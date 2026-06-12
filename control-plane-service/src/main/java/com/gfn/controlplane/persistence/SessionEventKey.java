package com.gfn.controlplane.persistence;

import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@PrimaryKeyClass
public class SessionEventKey implements Serializable {
    @PrimaryKeyColumn(name = "session_id", type = PrimaryKeyType.PARTITIONED)
    private String sessionId;

    @PrimaryKeyColumn(name = "created_at", ordinal = 0, type = PrimaryKeyType.CLUSTERED)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "event_id", ordinal = 1, type = PrimaryKeyType.CLUSTERED)
    private UUID eventId;

    public SessionEventKey() {
    }

    public SessionEventKey(String sessionId, Instant createdAt, UUID eventId) {
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.eventId = eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getEventId() {
        return eventId;
    }
}

