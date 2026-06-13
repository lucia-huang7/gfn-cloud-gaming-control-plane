package com.gfn.controlplane.events;

import com.gfn.controlplane.persistence.SessionEvent;
import com.gfn.controlplane.persistence.SessionEventRepository;
import com.gfn.controlplane.session.SessionRecord;
import com.gfn.controlplane.state.RedisStateStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SessionEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(SessionEventPublisher.class);

    private final SessionEventRepository eventRepository;
    private final RedisStateStore stateStore;
    private final Counter persistedCounter;
    private final Counter deadLetterCounter;

    public SessionEventPublisher(
            SessionEventRepository eventRepository,
            RedisStateStore stateStore,
            MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.stateStore = stateStore;
        this.persistedCounter = Counter.builder("gfn_session_events_persisted_total")
                .description("Session events persisted to Cassandra")
                .register(meterRegistry);
        this.deadLetterCounter = Counter.builder("gfn_session_events_dead_lettered_total")
                .description("Session events written to Redis dead letter storage after Cassandra failure")
                .register(meterRegistry);
    }

    public void publish(SessionRecord session, String eventType) {
        SessionEvent event = SessionEvent.from(session, eventType);
        try {
            eventRepository.save(event);
            persistedCounter.increment();
        } catch (RuntimeException ex) {
            deadLetterCounter.increment();
            stateStore.deadLetterSessionEvent(event, ex);
            log.error(
                    "Failed to persist session event to Cassandra; event dead-lettered. sessionId={} eventType={}",
                    session.sessionId(),
                    eventType,
                    ex
            );
        }
    }
}

