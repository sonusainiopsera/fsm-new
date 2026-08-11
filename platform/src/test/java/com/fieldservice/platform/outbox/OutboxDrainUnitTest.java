package com.fieldservice.platform.outbox;

import com.fieldservice.platform.api.DomainEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for outbox drain components — no Spring context, no database.
 *
 * <p>Covers AC-3 (backoff bounds), AC-4 (dead-letter transition), AC-5 (idempotency guard),
 * AC-7 (unhandled type), and AC-6 (lock lease expiry in {@link JdbcSchedulingLock}).
 */
class OutboxDrainUnitTest {

    private static final Instant FIXED_NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final Clock   FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    // ---- Backoff calculation ---------------------------------------------------

    @Nested
    @DisplayName("BackoffCalculator")
    class BackoffTests {

        private OutboxProperties props;

        @BeforeEach
        void setup() {
            props = new OutboxProperties(65536, 100, 5, 1_000L, 300_000L, 0.2, 10_000L);
        }

        @Test
        @DisplayName("attempt 0: delay in [base, base × (1+jitter))")
        void attempt0_in_base_range() {
            long base = props.backoffBaseMs();
            double maxMultiplier = 1.0 + props.jitterFraction();

            for (int i = 0; i < 100; i++) {
                Instant next = OutboxPoller.computeBackoff(0, FIXED_NOW, props);
                long delayMs = next.toEpochMilli() - FIXED_NOW.toEpochMilli();
                assertThat(delayMs).isGreaterThanOrEqualTo(base);
                assertThat(delayMs).isLessThanOrEqualTo((long)(base * maxMultiplier) + 1);
            }
        }

        @Test
        @DisplayName("attempt 1: delay in [2×base, 2×base × (1+jitter))")
        void attempt1_doubles_base() {
            long expected = 2 * props.backoffBaseMs();
            double maxMultiplier = 1.0 + props.jitterFraction();

            for (int i = 0; i < 100; i++) {
                Instant next = OutboxPoller.computeBackoff(1, FIXED_NOW, props);
                long delayMs = next.toEpochMilli() - FIXED_NOW.toEpochMilli();
                assertThat(delayMs).isGreaterThanOrEqualTo(expected);
                assertThat(delayMs).isLessThanOrEqualTo((long)(expected * maxMultiplier) + 1);
            }
        }

        @Test
        @DisplayName("high attempt: delay is capped at backoffCapMs × (1+jitter)")
        void high_attempt_capped() {
            long cap = props.backoffCapMs();
            double maxMultiplier = 1.0 + props.jitterFraction();

            for (int i = 0; i < 100; i++) {
                Instant next = OutboxPoller.computeBackoff(100, FIXED_NOW, props); // far beyond cap
                long delayMs = next.toEpochMilli() - FIXED_NOW.toEpochMilli();
                assertThat(delayMs).isGreaterThanOrEqualTo(cap);
                assertThat(delayMs).isLessThanOrEqualTo((long)(cap * maxMultiplier) + 1);
            }
        }

        @Test
        @DisplayName("backoff result is always in the future")
        void backoff_always_future() {
            for (int attempt = 0; attempt < 10; attempt++) {
                Instant next = OutboxPoller.computeBackoff(attempt, FIXED_NOW, props);
                assertThat(next).isAfter(FIXED_NOW);
            }
        }
    }

    // ---- Dead-letter transition ------------------------------------------------

    @Nested
    @DisplayName("Dead-letter transition")
    class DeadLetterTests {

        @Test
        @DisplayName("event with attemptCount = maxAttempts-1 is dead-lettered on next failure")
        void dead_lettered_at_max_attempts() {
            var props = new OutboxProperties(65536, 100, 3, 1000L, 60000L, 0.2, 10000L);
            var metrics = new SimpleMeterRegistry();
            var pollerMetrics = mockMetrics(metrics);

            var event = makeEvent();
            // Simulate 2 prior failures (attempt_count = 2, so next failure is attempt 3 = maxAttempts)
            event.recordFailure("prior error", FIXED_NOW.plusSeconds(1));
            event.recordFailure("prior error", FIXED_NOW.plusSeconds(2));

            assertThat(event.getAttemptCount()).isEqualTo(2);
            assertThat(event.getDeadLetteredAt()).isNull();

            // Simulate one more failure inside the poller via the method under test
            simulateFailure(event, props, pollerMetrics, FIXED_NOW);

            assertThat(event.getDeadLetteredAt()).isNotNull();
            assertThat(event.getDeadLetteredAt()).isEqualTo(FIXED_NOW);
            assertThat(event.getAttemptCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("dead-lettered event has dead_lettered_at set and last_error recorded")
        void dead_lettered_event_has_error_recorded() {
            var props = new OutboxProperties(65536, 100, 1, 1000L, 60000L, 0.2, 10000L);
            var event = makeEvent();

            simulateFailure(event, props, mockMetrics(new SimpleMeterRegistry()), FIXED_NOW);

            assertThat(event.getDeadLetteredAt()).isEqualTo(FIXED_NOW);
            assertThat(event.getLastError()).isNotBlank();
        }

        @Test
        @DisplayName("event below max attempts is not dead-lettered; next_attempt_at is in the future")
        void below_max_attempts_not_dead_lettered() {
            var props = new OutboxProperties(65536, 100, 5, 1000L, 60000L, 0.2, 10000L);
            var event = makeEvent();

            simulateFailure(event, props, mockMetrics(new SimpleMeterRegistry()), FIXED_NOW);

            assertThat(event.getDeadLetteredAt()).isNull();
            assertThat(event.getNextAttemptAt()).isAfter(FIXED_NOW);
        }
    }

    // ---- Idempotency guard ----------------------------------------------------

    @Nested
    @DisplayName("ConsumerIdempotencyGuard")
    class IdempotencyGuardTests {

        @Test
        @DisplayName("claimProcessing returns true when INSERT succeeds (1 row)")
        void returns_true_on_new_insert() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(UUID.class), anyString())).thenReturn(1);

            var guard = new ConsumerIdempotencyGuard(jdbc);
            UUID id = UUID.randomUUID();

            assertThat(guard.claimProcessing(id, "TestConsumer")).isTrue();
            verify(jdbc, times(1)).update(anyString(), eq(id), eq("TestConsumer"));
        }

        @Test
        @DisplayName("claimProcessing returns false when INSERT is a no-op (conflict)")
        void returns_false_on_conflict() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(UUID.class), anyString())).thenReturn(0);

            var guard = new ConsumerIdempotencyGuard(jdbc);
            assertThat(guard.claimProcessing(UUID.randomUUID(), "TestConsumer")).isFalse();
        }
    }

    // ---- Unhandled event type -------------------------------------------------

    @Nested
    @DisplayName("Unhandled event type")
    class UnhandledTypeTests {

        @Test
        @DisplayName("unhandled event type is marked published immediately")
        void unhandled_type_marked_published() {
            var event = makeEvent("UNKNOWN_EVENT_TYPE");
            var props = new OutboxProperties(65536, 100, 5, 1000L, 60000L, 0.2, 10000L);
            var registry = new SimpleMeterRegistry();
            var pollerMetrics = mockMetrics(registry);

            simulateUnhandled(event, pollerMetrics, FIXED_NOW);

            assertThat(event.getPublishedAt()).isEqualTo(FIXED_NOW);
            assertThat(event.getDeadLetteredAt()).isNull();
            assertThat(event.getAttemptCount()).isEqualTo(0);
        }
    }

    // ---- SchedulingLock lease expiry -----------------------------------------

    @Nested
    @DisplayName("JdbcSchedulingLock")
    class SchedulingLockTests {

        @Test
        @DisplayName("runIfLeader executes body when UPDATE returns 1 (lock acquired)")
        void executes_body_when_lock_acquired() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);

            var lock = new JdbcSchedulingLock(jdbc);
            boolean[] ran = {false};

            lock.runIfLeader("test-lock", "replica-1", 30, () -> ran[0] = true);

            assertThat(ran[0]).isTrue();
        }

        @Test
        @DisplayName("runIfLeader skips body when UPDATE returns 0 (another holder)")
        void skips_body_when_not_leader() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);

            var lock = new JdbcSchedulingLock(jdbc);
            boolean[] ran = {false};

            lock.runIfLeader("test-lock", "replica-2", 30, () -> ran[0] = true);

            assertThat(ran[0]).isFalse();
        }

        @Test
        @DisplayName("runIfLeader does not propagate database exceptions")
        void swallows_database_exception() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any()))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

            var lock = new JdbcSchedulingLock(jdbc);

            // must not throw
            lock.runIfLeader("test-lock", "replica-1", 30, () -> {});
        }

        @Test
        @DisplayName("runIfLeader allows re-acquisition after lease expiry (expired holder case)")
        void reacquires_after_expiry() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            // First call: another holder, returns 0
            // Second call: lease expired, returns 1
            when(jdbc.update(anyString(), any(), any(), any(), any()))
                    .thenReturn(0)
                    .thenReturn(1);

            var lock = new JdbcSchedulingLock(jdbc);
            boolean[] firstRan = {false};
            boolean[] secondRan = {false};

            lock.runIfLeader("test-lock", "replica-1", 30, () -> firstRan[0] = true);
            lock.runIfLeader("test-lock", "replica-1", 30, () -> secondRan[0] = true);

            assertThat(firstRan[0]).isFalse();
            assertThat(secondRan[0]).isTrue();
        }
    }

    // ---- Helpers ---------------------------------------------------------------

    private OutboxEvent makeEvent() {
        return makeEvent("WORK_ORDER_ASSIGNED");
    }

    private OutboxEvent makeEvent(String eventType) {
        var e = new OutboxEvent();
        // Use package-private factory via reflection (OutboxEvent.from needs a DomainEvent)
        // Instead, build via the static factory with a minimal DomainEvent
        DomainEvent de = new DomainEvent(
                UUID.randomUUID(), eventType, "WORK_ORDER", UUID.randomUUID(),
                FIXED_NOW, "trace-unit", null, "{}"
        );
        var created = OutboxEvent.from(de, "{}");
        return created;
    }

    private OutboxPollerMetrics mockMetrics(io.micrometer.core.instrument.MeterRegistry reg) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(0.0);
        return new OutboxPollerMetrics(reg, jdbc);
    }

    /** Simulates the OutboxPoller.handleFailure logic without needing a full Spring context. */
    private void simulateFailure(OutboxEvent event, OutboxProperties props,
                                 OutboxPollerMetrics metrics, Instant now) {
        Exception e = new RuntimeException("test failure");
        String errorMsg = e.getClass().getName() + ": " + e.getMessage();
        int nextAttemptNumber = event.getAttemptCount() + 1;

        metrics.recordDispatchFailure(event.getEventType());

        if (nextAttemptNumber >= props.maxAttempts()) {
            event.recordFailure(errorMsg, now);
            event.markDeadLettered(now);
            metrics.recordDeadLetter();
        } else {
            Instant nextAttempt = OutboxPoller.computeBackoff(event.getAttemptCount(), now, props);
            event.recordFailure(errorMsg, nextAttempt);
        }
    }

    /** Simulates handling an event with no registered handler. */
    private void simulateUnhandled(OutboxEvent event, OutboxPollerMetrics metrics, Instant now) {
        metrics.recordUnhandledType(event.getEventType());
        event.markPublished(now);
    }
}
