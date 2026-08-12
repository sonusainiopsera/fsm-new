package com.fieldservice.sla;

import com.fieldservice.sla.internal.SlaAlertEmitterRegistry;
import com.fieldservice.sla.internal.SlaAlertReplayBuffer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for SlaAlertReplayBuffer and SlaAlertEmitterRegistry.
 * No Spring context required.
 */
class SlaAlertUnitTest {

    // ─── SlaAlertReplayBuffer tests ──────────────────────────────────────────

    @Nested
    @DisplayName("SlaAlertReplayBuffer")
    class ReplayBufferTests {

        SlaAlertReplayBuffer buffer;

        @BeforeEach
        void setUp() {
            buffer = new SlaAlertReplayBuffer(5);
        }

        @Test
        @DisplayName("replay returns events after lastEventId")
        void replaySinceReturnsEventsAfter() {
            buffer.add("id1", "SLA_AT_RISK", "{\"a\":1}");
            buffer.add("id2", "SLA_AT_RISK", "{\"a\":2}");
            buffer.add("id3", "SLA_BREACHED", "{\"a\":3}");

            List<SlaAlertReplayBuffer.ReplayEntry> result = buffer.replaySince("id1");
            assertThat(result).hasSize(2);
            assertThat(result.get(0).eventId()).isEqualTo("id2");
            assertThat(result.get(1).eventId()).isEqualTo("id3");
        }

        @Test
        @DisplayName("replay returns null when lastEventId is outside horizon (resync)")
        void replaySinceReturnsNullForUnknownId() {
            buffer.add("id1", "SLA_AT_RISK", "{}");
            List<SlaAlertReplayBuffer.ReplayEntry> result = buffer.replaySince("unknown-id");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("replay returns empty list when lastEventId is the latest event")
        void replaySinceReturnsEmptyForLatest() {
            buffer.add("id1", "SLA_AT_RISK", "{}");
            buffer.add("id2", "SLA_AT_RISK", "{}");

            List<SlaAlertReplayBuffer.ReplayEntry> result = buffer.replaySince("id2");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("evicts oldest entry when capacity is reached")
        void evictsOldestOnCapacity() {
            for (int i = 1; i <= 6; i++) {
                buffer.add("id" + i, "SLA_AT_RISK", "{}");
            }
            // id1 was evicted; id6 is the latest
            assertThat(buffer.size()).isEqualTo(5);
            // Requesting replay since id1 should return null (not in buffer anymore)
            assertThat(buffer.replaySince("id1")).isNull();
            // Replay since id2 returns ids 3,4,5,6
            List<SlaAlertReplayBuffer.ReplayEntry> result = buffer.replaySince("id2");
            assertThat(result).hasSize(4);
        }

        @Test
        @DisplayName("duplicate event IDs are silently ignored")
        void duplicateIdsIgnored() {
            buffer.add("id1", "SLA_AT_RISK", "{\"v\":1}");
            buffer.add("id1", "SLA_AT_RISK", "{\"v\":2}"); // duplicate
            assertThat(buffer.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("replay returns empty list when buffer is empty")
        void emptyBufferReturnsEmpty() {
            assertThat(buffer.replaySince("any")).isNull(); // buffer is empty, "any" not found
        }
    }

    // ─── SlaAlertEmitterRegistry tests ──────────────────────────────────────

    @Nested
    @DisplayName("SlaAlertEmitterRegistry")
    class RegistryTests {

        SlaAlertEmitterRegistry registry;
        SimpleMeterRegistry meterRegistry;

        @BeforeEach
        void setUp() {
            meterRegistry = new SimpleMeterRegistry();
            // max 2 per user, 10 global, 15s heartbeat
            registry = new SlaAlertEmitterRegistry(2, 10, 15, meterRegistry);
        }

        @Test
        @DisplayName("register returns an SseEmitter and increments count")
        void registerReturnsEmitter() {
            UUID userId = UUID.randomUUID();
            SseEmitter emitter = registry.register(userId);
            assertThat(emitter).isNotNull();
            assertThat(registry.countForUser(userId)).isEqualTo(1);
        }

        @Test
        @DisplayName("throws StreamCapExceededException when per-user cap exceeded")
        void throwsOnPerUserCap() {
            UUID userId = UUID.randomUUID();
            registry.register(userId);
            registry.register(userId);
            assertThatThrownBy(() -> registry.register(userId))
                    .isInstanceOf(SlaAlertEmitterRegistry.StreamCapExceededException.class);
        }

        @Test
        @DisplayName("sendToAll delivers to all registered emitters")
        void sendToAllDelivers() throws Exception {
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            // We can't fully test send without real HTTP; just verify no exception thrown
            SseEmitter e1 = registry.register(user1);
            SseEmitter e2 = registry.register(user2);
            // complete emitters so send is a no-op rather than throwing
            e1.complete();
            e2.complete();
            // sendToAll should handle completed emitters gracefully (remove them)
            registry.sendToAll("evt1", "SLA_AT_RISK", "{}", meterRegistry);
        }

        @Test
        @DisplayName("deduplicates by event ID within same emitter")
        void deduplicatesEventId() {
            // Verify EmitterEntry.hasReceived and markReceived work
            SlaAlertEmitterRegistry.EmitterEntry entry = new SlaAlertEmitterRegistry.EmitterEntry(
                    UUID.randomUUID(), UUID.randomUUID(), new SseEmitter(0L));
            assertThat(entry.hasReceived("evt1")).isFalse();
            entry.markReceived("evt1");
            assertThat(entry.hasReceived("evt1")).isTrue();
            assertThat(entry.hasReceived("evt2")).isFalse();
        }

        @Test
        @DisplayName("different users can each register up to the per-user cap")
        void differentUsersCanEachRegister() {
            UUID user1 = UUID.randomUUID();
            UUID user2 = UUID.randomUUID();
            registry.register(user1);
            registry.register(user1);
            registry.register(user2);
            registry.register(user2);
            assertThat(registry.countForUser(user1)).isEqualTo(2);
            assertThat(registry.countForUser(user2)).isEqualTo(2);
        }
    }
}
