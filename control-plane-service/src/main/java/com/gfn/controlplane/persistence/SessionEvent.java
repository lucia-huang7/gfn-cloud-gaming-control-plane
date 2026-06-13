package com.gfn.controlplane.persistence;

import com.gfn.controlplane.session.SessionRecord;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("session_events_by_session")
public class SessionEvent {
    @PrimaryKey
    private SessionEventKey key;
    @Column("event_type")
    private String eventType;
    @Column("region")
    private String region;
    @Column("gpu_profile")
    private String gpuProfile;
    @Column("node_id")
    private String nodeId;

    public static SessionEvent from(SessionRecord session, String eventType) {
        SessionEvent event = new SessionEvent();
        event.key = new SessionEventKey(session.sessionId(), Instant.now(), UUID.randomUUID());
        event.eventType = eventType;
        event.region = session.region().name();
        event.gpuProfile = session.gpuProfile().name();
        event.nodeId = session.nodeId();
        return event;
    }

    public SessionEventKey getKey() {
        return key;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRegion() {
        return region;
    }

    public String getGpuProfile() {
        return gpuProfile;
    }

    public String getNodeId() {
        return nodeId;
    }
}
