package com.fieldservice.sla;

import com.fieldservice.sla.internal.SlaAlertEmitterRegistry;
import com.fieldservice.sla.internal.SlaAlertStreamProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class SlaAlertEmitterRegistryTest {

    private SlaAlertEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        SlaAlertStreamProperties props = new SlaAlertStreamProperties();
        props.setMaxConcurrentPerUser(2);
        props.setHeartbeatIntervalSeconds(3600); // prevent heartbeats during tests
        registry = new SlaAlertEmitterRegistry(props, new SimpleMeterRegistry());
        registry.startHeartbeat();
    }

    @AfterEach
    void tearDown() {
        registry.stopHeartbeat();
    }

    @Test
    void register_firstEmitter_returnsTrue() {
        assertThat(registry.register("alice", new SseEmitter())).isTrue();
    }

    @Test
    void register_upToCap_allSucceed() {
        assertThat(registry.register("alice", new SseEmitter())).isTrue();
        assertThat(registry.register("alice", new SseEmitter())).isTrue();
    }

    @Test
    void register_exceedsCap_returnsFalse() {
        registry.register("alice", new SseEmitter());
        registry.register("alice", new SseEmitter());

        assertThat(registry.register("alice", new SseEmitter())).isFalse();
    }

    @Test
    void register_capIsPerUser_otherUserUnaffected() {
        registry.register("alice", new SseEmitter());
        registry.register("alice", new SseEmitter());

        assertThat(registry.register("bob", new SseEmitter())).isTrue();
    }

    @Test
    void hasActiveSubscribers_empty_returnsFalse() {
        assertThat(registry.hasActiveSubscribers()).isFalse();
    }

    @Test
    void hasActiveSubscribers_withEmitter_returnsTrue() {
        registry.register("alice", new SseEmitter());
        assertThat(registry.hasActiveSubscribers()).isTrue();
    }

    @Test
    void onCompletion_removesEmitter_countDropsToZero() {
        SseEmitter emitter = new SseEmitter();
        registry.register("alice", emitter);
        assertThat(registry.hasActiveSubscribers()).isTrue();

        emitter.complete();

        assertThat(registry.hasActiveSubscribers()).isFalse();
    }

    @Test
    void fanOut_noSubscribers_returnsZero() {
        int count = registry.fanOut("evt-1", SlaAlertEmitterRegistry.EVENT_SLA_AT_RISK, "{}");
        assertThat(count).isEqualTo(0);
    }

    @Test
    void fanOut_withOneSubscriber_returnsOne() {
        SseEmitter emitter = new SseEmitter();
        registry.register("alice", emitter);

        int count = registry.fanOut("evt-2", SlaAlertEmitterRegistry.EVENT_SLA_BREACHED, "{}");

        assertThat(count).isEqualTo(1);
    }

    @Test
    void fanOut_deduplicatesSameEventId() {
        SseEmitter emitter = new SseEmitter();
        registry.register("alice", emitter);

        int first  = registry.fanOut("evt-dup", SlaAlertEmitterRegistry.EVENT_SLA_AT_RISK, "{}");
        int second = registry.fanOut("evt-dup", SlaAlertEmitterRegistry.EVENT_SLA_AT_RISK, "{}");

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    @Test
    void fanOut_multipleUsers_sendsToAll() {
        registry.register("alice", new SseEmitter());
        registry.register("bob", new SseEmitter());

        int count = registry.fanOut("evt-3", SlaAlertEmitterRegistry.EVENT_SLA_BREACHED, "{}");

        assertThat(count).isEqualTo(2);
    }

    @Test
    void register_afterCapReleasedByCompletion_allowsNewEmitter() {
        SseEmitter first = new SseEmitter();
        registry.register("alice", first);
        registry.register("alice", new SseEmitter());
        assertThat(registry.register("alice", new SseEmitter())).isFalse();

        first.complete(); // frees a slot

        assertThat(registry.register("alice", new SseEmitter())).isTrue();
    }
}
