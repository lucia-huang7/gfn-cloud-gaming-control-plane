package com.gfn.controlplane.events;

import com.gfn.controlplane.persistence.SessionEvent;
import com.gfn.controlplane.persistence.SessionEventRepository;
import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import com.gfn.controlplane.session.SessionRecord;
import com.gfn.controlplane.state.RedisStateStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionEventPublisherTest {
    private Level previousLevel;

    @BeforeEach
    void quietExpectedFailureLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(SessionEventPublisher.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
    }

    @AfterEach
    void restoreLogLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(SessionEventPublisher.class);
        logger.setLevel(previousLevel);
    }

    @Test
    void deadLettersEventWhenCassandraWriteFails() {
        SessionEventRepository repository = mock(SessionEventRepository.class);
        RedisStateStore stateStore = mock(RedisStateStore.class);
        SessionEventPublisher publisher = new SessionEventPublisher(repository, stateStore, new SimpleMeterRegistry());
        SessionRecord session = new SessionRecord(
                "sess_1",
                "tenant_a",
                "user_123",
                "cyberpunk2077",
                Region.US_WEST,
                GpuProfile.ULTRA,
                45
        );

        RuntimeException failure = new RuntimeException("cassandra unavailable");
        when(repository.save(any(SessionEvent.class))).thenThrow(failure);

        publisher.publish(session, "SESSION_QUEUED");

        verify(stateStore).deadLetterSessionEvent(any(SessionEvent.class), org.mockito.Mockito.eq(failure));
    }
}
