package com.fieldservice.outbox;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import com.fieldservice.security.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the outbox drain pipeline (WO-005).
 *
 * <p>Tests use the {@code test} profile (Testcontainers PostgreSQL); the
 * {@code worker} profile is intentionally absent so the {@link OutboxPoller}
 * scheduler does not fire. The drain service is called directly for determinism.
 *
 * <p>Delivery is at-least-once by design. These tests assert that:
 * <ul>
 *   <li>250 events are fully drained with each handler invoked once.</li>
 *   <li>Two concurrent instances process disjoint event sets (SKIP LOCKED).</li>
 *   <li>Failures increment attempt_count with backoff, then dead-letter.</li>
 *   <li>Processed-event guard makes replayed events no-ops.</li>
 *   <li>Exactly one replica executes a guarded sweep per lease window.</li>
 * </ul>
 */
@Import(OutboxPollerIntegrationTest.TestHandlerConfig.class)
@DisplayName("Outbox drain integration tests (WO-005)")
class OutboxPollerIntegrationTest extends AbstractIntegrationTest {

    // -----------------------------------------------------------------------
    // Test handler beans
    // -----------------------------------------------------------------------

    @TestConfiguration
    static class TestHandlerConfig {

        @Bean
        CountingEventHandler countingEventHandler(IdempotencyGuard guard) {
            return new CountingEventHandler(guard);
        }

        @Bean
        AlwaysFailingEventHandler alwaysFailingEventHandler() {
            return new AlwaysFailingEventHandler();
        }
    }

    /** Counts handler invocations; resets per test via {@link #reset()}. */
    static class CountingEventHandler implements EventHandler {
        static final String TYPE = "WorkOrderStateChanged";
        private final IdempotencyGuard guard;
        private final AtomicInteger count = new AtomicInteger(0);

        CountingEventHandler(IdempotencyGuard guard) { this.guard = guard; }

        @Override public String getSupportedEventType() { return TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) {
            if (!guard.claimEvent(ctx.eventId(), "CountingEventHandler")) return;
            count.incrementAndGet();
        }

        int getCount() { return count.get(); }
        void reset() { count.set(0); }
    }

    /** Always throws; used for dead-letter test. */
    static class AlwaysFailingEventHandler implements EventHandler {
        static final String TYPE = "AlwaysFails";

        @Override public String getSupportedEventType() { return TYPE; }

        @Override
        public void handle(EventHandlerContext ctx) throws Exception {
            throw new RuntimeException("Deliberate failure for dead-letter test");
        }
    }

    // -----------------------------------------------------------------------
    // Wired beans
    // -----------------------------------------------------------------------

    @Autowired OutboxDrainService drainService;
    @Autowired DomainEventPublisher publisher;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate txTemplate;
    @Autowired OutboxPollerProperties properties;
    @Autowired MeterRegistry meterRegistry;
    @Autowired CountingEventHandler countingHandler;

    @BeforeEach
    void cleanOutbox() {
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM scheduler_lock");
        countingHandler.reset();
    }

    // -----------------------------------------------------------------------
    // 1. Drain 250 events
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Drains 250 events: all published_at set, handler invoked exactly once each")
    void drain_250Events_allPublishedHandlerFiredOnce() {
        UUID actorId = UUID.randomUUID();
        UUID workOrderId = UUID.randomUUID();

        // Publish 250 events inside a transaction (publisher requires active TX)
        txTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < 250; i++) {
                publisher.publish(WorkOrderEventFixtureBuilder.workOrderStateChanged(
                        workOrderId, "NEW", "ASSIGNED", "HIGH", actorId));
            }
        });

        // Drain in passes until queue is empty
        int totalDrained = 0;
        int pass;
        do {
            pass = drainService.drainBatch(100);
            totalDrained += pass;
        } while (pass > 0);

        assertThat(totalDrained).isEqualTo(250);
        assertThat(countingHandler.getCount()).isEqualTo(250);

        // All events must have published_at set
        Long unpublished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        assertThat(unpublished).isZero();
    }

    // -----------------------------------------------------------------------
    // 2. Concurrency: disjoint processing, zero duplicate handler invocations
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Two concurrent drain threads process disjoint event sets with no duplicates")
    void concurrency_disjointProcessing_noDuplicateInvocations() throws InterruptedException {
        UUID actorId = UUID.randomUUID();
        UUID workOrderId = UUID.randomUUID();

        // Publish 100 events
        txTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < 100; i++) {
                publisher.publish(WorkOrderEventFixtureBuilder.workOrderStateChanged(
                        workOrderId, "NEW", "ASSIGNED", "HIGH", actorId));
            }
        });

        CountDownLatch start = new CountDownLatch(1);
        List<Integer> results = new ArrayList<>(2);
        Object lock = new Object();

        Thread t1 = Thread.ofVirtual().start(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            int count = drainService.drainBatch(100);
            synchronized (lock) { results.add(count); }
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            int count = drainService.drainBatch(100);
            synchronized (lock) { results.add(count); }
        });

        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        // Total processed = 100, with disjoint sets
        int total = results.stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo(100);
        assertThat(countingHandler.getCount()).isEqualTo(100);

        // No duplicate processed_event rows (PK would have prevented it, but verify count)
        Long processedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE consumer_name = 'CountingEventHandler'",
                Long.class);
        assertThat(processedCount).isEqualTo(100L);
    }

    // -----------------------------------------------------------------------
    // 3. Failure, backoff, and dead-letter transition
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Failing handler: attempt_count increments with backoff, dead-lettered after max attempts")
    void failingHandler_retryBackoff_deadLetterAfterMaxAttempts() {
        // Set low max attempts for the test
        int savedMax = properties.getMaxAttempts();
        long savedBase = properties.getBackoffBaseMs();
        properties.setMaxAttempts(3);
        properties.setBackoffBaseMs(0); // no wait between retries

        try {
            UUID workOrderId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            // Publish one AlwaysFails event via direct SQL (no fixture builder for custom types)
            jdbcTemplate.update("""
                    INSERT INTO outbox_event
                        (event_id, event_type, aggregate_type, aggregate_id, payload,
                         trace_id, actor_user_id, attempt_count, next_attempt_at)
                    VALUES (?, ?, 'WorkOrder', ?, '{"test":true}'::jsonb,
                            'test-trace', NULL, 0, now())
                    """,
                    eventId, AlwaysFailingEventHandler.TYPE, workOrderId);

            // Drain pass 1: attempt 1 fails, attempt_count = 1
            drainService.drainBatch(1);
            Integer attempt1 = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM outbox_event WHERE event_id = ?",
                    Integer.class, eventId);
            assertThat(attempt1).isEqualTo(1);

            // Reset next_attempt_at to make eligible again (bypass backoff in test)
            jdbcTemplate.update("UPDATE outbox_event SET next_attempt_at = now() WHERE event_id = ?", eventId);

            // Drain pass 2: attempt 2 fails
            drainService.drainBatch(1);
            jdbcTemplate.update("UPDATE outbox_event SET next_attempt_at = now() WHERE event_id = ?", eventId);

            // Drain pass 3: attempt 3 = max; event dead-lettered
            drainService.drainBatch(1);

            Integer finalAttempt = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM outbox_event WHERE event_id = ?",
                    Integer.class, eventId);
            Object deadLetteredAt = jdbcTemplate.queryForObject(
                    "SELECT dead_lettered_at FROM outbox_event WHERE event_id = ?",
                    Object.class, eventId);

            assertThat(finalAttempt).isEqualTo(3);
            assertThat(deadLetteredAt).as("dead_lettered_at should be set").isNotNull();

            // Event must be excluded from further claims (SKIP LOCKED condition)
            int afterDeadLetter = drainService.drainBatch(1);
            assertThat(afterDeadLetter).isZero();

            // Dead-letter metric incremented
            double dlCount = meterRegistry.counter("outbox.dead.letter",
                    "event_type", AlwaysFailingEventHandler.TYPE).count();
            assertThat(dlCount).isGreaterThanOrEqualTo(1.0);

        } finally {
            properties.setMaxAttempts(savedMax);
            properties.setBackoffBaseMs(savedBase);
        }
    }

    // -----------------------------------------------------------------------
    // 4. Consumer idempotency guard
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Replaying a processed event is a no-op: side effect occurs exactly once")
    void idempotency_replayEvent_sideEffectExactlyOnce() {
        UUID workOrderId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        DomainEvent event = WorkOrderEventFixtureBuilder.workOrderStateChangedWithId(
                eventId, workOrderId, "NEW", "ASSIGNED", "HIGH", actorId);

        // Publish and drain: handler fires once
        txTemplate.executeWithoutResult(status -> publisher.publish(event));
        drainService.drainBatch(1);
        assertThat(countingHandler.getCount()).isEqualTo(1);

        // Simulate crash-then-replay: reset published_at to NULL (as if worker crashed
        // after dispatch but before marking published)
        jdbcTemplate.update("UPDATE outbox_event SET published_at = NULL, next_attempt_at = now() WHERE event_id = ?", eventId);

        // Drain again: processed_event guard prevents a second handler invocation
        drainService.drainBatch(1);
        assertThat(countingHandler.getCount())
                .as("Side effect must not repeat on replay")
                .isEqualTo(1);

        // Event is now published (drain service marks it even though handler was skipped)
        Long unpublished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_id = ? AND published_at IS NULL",
                Long.class, eventId);
        assertThat(unpublished).isZero();
    }

    // -----------------------------------------------------------------------
    // 5. Unknown event type is marked published without blocking the queue
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Unknown event type is marked published, queue progresses, unhandled metric increments")
    void unknownEventType_markedPublished_queueProgresses() {
        UUID workOrderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (event_id, event_type, aggregate_type, aggregate_id, payload,
                     attempt_count, next_attempt_at)
                VALUES (?, 'UnknownEventType', 'WorkOrder', ?, '{"test":true}'::jsonb, 0, now())
                """, eventId, workOrderId);

        // Publish a known event after the unknown one to verify queue progresses
        UUID knownWorkOrderId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        txTemplate.executeWithoutResult(status -> publisher.publish(
                WorkOrderEventFixtureBuilder.workOrderStateChanged(
                        knownWorkOrderId, "NEW", "ASSIGNED", "HIGH", actorId)));

        drainService.drainBatch(10);

        Long unpublished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
        assertThat(unpublished).isZero();
        assertThat(countingHandler.getCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 6. Distributed lock: leader election and lease expiry
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Two simulated replicas: exactly one executes the guarded sweep per lease window")
    void leaderElection_exactlyOneExecutesPerWindow() throws InterruptedException {
        DatabaseSchedulingLock lock1 = new DatabaseSchedulingLock(jdbcTemplate);
        DatabaseSchedulingLock lock2 = new DatabaseSchedulingLock(jdbcTemplate);

        AtomicInteger executions = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);

        Thread t1 = Thread.ofVirtual().start(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            lock1.runIfLeader("test-sweep", Duration.ofSeconds(5), executions::incrementAndGet);
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            lock2.runIfLeader("test-sweep", Duration.ofSeconds(5), executions::incrementAndGet);
        });

        start.countDown();
        t1.join(5_000);
        t2.join(5_000);

        assertThat(executions.get())
                .as("Exactly one replica should execute per lease window")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Expired lease is re-acquirable by a different replica")
    void leaderElection_expiredLease_reacquiredByOtherReplica() throws InterruptedException {
        DatabaseSchedulingLock lock1 = new DatabaseSchedulingLock(jdbcTemplate);
        DatabaseSchedulingLock lock2 = new DatabaseSchedulingLock(jdbcTemplate);

        // Lock1 acquires with a very short lease
        boolean first = lock1.tryAcquireLease("test-expiry", Duration.ofMillis(200));
        assertThat(first).isTrue();

        // Lock2 cannot acquire while lease is valid
        boolean blocked = lock2.tryAcquireLease("test-expiry", Duration.ofSeconds(5));
        assertThat(blocked).isFalse();

        // Wait for lock1's lease to expire
        Thread.sleep(300);

        // Now lock2 can acquire
        boolean reacquired = lock2.tryAcquireLease("test-expiry", Duration.ofSeconds(5));
        assertThat(reacquired).isTrue();
    }
}
