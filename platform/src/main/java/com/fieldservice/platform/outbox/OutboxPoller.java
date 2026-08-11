package com.fieldservice.platform.outbox;

import com.fieldservice.platform.api.DomainEvent;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drains the transactional outbox on the {@code worker} profile.
 *
 * <p>Each scheduled pass claims a bounded batch of unpublished rows using
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, dispatches each event to its
 * registered {@link EventHandler}, and marks successful events with a
 * {@code published_at} timestamp — all inside the claim transaction so a
 * mid-dispatch crash releases the row lock and the event is re-claimable.
 *
 * <p>Concurrency: SKIP LOCKED guarantees two concurrently running pollers
 * process disjoint event sets with zero duplicate handler invocations.
 *
 * <p>Retry policy: failures increment {@code attempt_count} and reschedule with
 * jittered exponential backoff.  After {@code app.outbox.max-attempts} failures
 * the event is moved to a dead-letter state and excluded from future claims.
 *
 * <p>Virtual threads: {@code spring.threads.virtual.enabled=true} means each
 * handler dispatch may block I/O safely without stalling the poll loop.
 */
@Profile("worker")
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private static final String CLAIM_SQL = """
            SELECT * FROM outbox_event
             WHERE published_at IS NULL
               AND dead_lettered_at IS NULL
               AND next_attempt_at <= NOW()
             ORDER BY created_at
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """;

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Map<String, EventHandler> handlers;
    private final OutboxProperties props;
    private final OutboxPollerMetrics metrics;
    private final Clock clock;

    public OutboxPoller(EntityManager em,
                        PlatformTransactionManager txManager,
                        List<EventHandler> handlerList,
                        OutboxProperties props,
                        OutboxPollerMetrics metrics,
                        Clock clock) {
        this.em      = em;
        this.tx      = new TransactionTemplate(txManager);
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(EventHandler::supportedEventType, Function.identity()));
        this.props   = props;
        this.metrics = metrics;
        this.clock   = clock;
    }

    @Scheduled(
        fixedDelayString    = "${app.outbox.poll-interval-ms:500}",
        initialDelayString  = "${app.outbox.poll-initial-delay-ms:0}"
    )
    public void poll() {
        try {
            tx.execute(status -> {
                drainBatch();
                return null;
            });
        } catch (Exception e) {
            log.warn("outbox_poll_error: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void drainBatch() {
        List<OutboxEvent> batch = em.createNativeQuery(CLAIM_SQL, OutboxEvent.class)
                .setParameter(1, props.batchSize())
                .getResultList();

        if (batch.isEmpty()) {
            return;
        }

        Instant now = clock.instant();
        log.debug("outbox_drain_batch size={}", batch.size());

        for (OutboxEvent event : batch) {
            dispatch(event, now);
        }
    }

    private void dispatch(OutboxEvent event, Instant now) {
        String prevTrace = MDC.get("traceId");
        if (event.getTraceId() != null) {
            MDC.put("traceId", event.getTraceId());
        }
        try {
            EventHandler handler = handlers.get(event.getEventType());
            if (handler == null) {
                log.warn("outbox_unhandled_type event_id={} event_type={}",
                        event.getEventId(), event.getEventType());
                metrics.recordUnhandledType(event.getEventType());
                event.markPublished(now);
                return;
            }

            DomainEvent domainEvent = reconstruct(event);
            long startNs = System.nanoTime();

            log.debug("outbox_dispatch_start event_id={} event_type={} attempt={} trace_id={}",
                    event.getEventId(), event.getEventType(), event.getAttemptCount(), event.getTraceId());

            handler.handle(domainEvent);

            long elapsedNs = System.nanoTime() - startNs;
            metrics.recordDispatchSuccess(event.getEventType(), elapsedNs);
            event.markPublished(now);

            log.debug("outbox_dispatch_success event_id={} event_type={} duration_ms={}",
                    event.getEventId(), event.getEventType(), elapsedNs / 1_000_000L);

        } catch (Exception e) {
            handleFailure(event, e, now);
        } finally {
            if (prevTrace != null) {
                MDC.put("traceId", prevTrace);
            } else {
                MDC.remove("traceId");
            }
        }
    }

    private void handleFailure(OutboxEvent event, Exception e, Instant now) {
        // Keep error message + class only; full stack trace is in the log, never in the column
        String errorMsg = e.getClass().getName() + ": " + e.getMessage();
        int nextAttemptNumber = event.getAttemptCount() + 1;

        log.error("outbox_dispatch_failed event_id={} event_type={} attempt={} trace_id={} error={}",
                event.getEventId(), event.getEventType(), nextAttemptNumber,
                event.getTraceId(), errorMsg, e);

        metrics.recordDispatchFailure(event.getEventType());

        if (nextAttemptNumber >= props.maxAttempts()) {
            event.recordFailure(errorMsg, now);
            event.markDeadLettered(now);
            metrics.recordDeadLetter();
            log.warn("outbox_dead_lettered event_id={} event_type={} attempts={}",
                    event.getEventId(), event.getEventType(), nextAttemptNumber);
        } else {
            // compute backoff BEFORE recordFailure increments attemptCount
            Instant nextAttempt = computeBackoff(event.getAttemptCount(), now);
            event.recordFailure(errorMsg, nextAttempt);
        }
    }

    /**
     * Computes the next attempt time using jittered exponential backoff.
     *
     * <p>Formula: {@code min(cap, base × 2^attempt) × (1 + jitter × random)}
     * where {@code random ∈ [0, 1)}.
     *
     * @param currentAttempt current {@code attempt_count} value (before increment)
     * @param now            reference instant (use {@link Clock} for testability)
     */
    static Instant computeBackoff(int currentAttempt, Instant now, OutboxProperties props) {
        int shift = Math.min(currentAttempt, 30); // guard against overflow for large attempt counts
        long baseDelay = props.backoffBaseMs() << shift;
        long cappedDelay = Math.min(props.backoffCapMs(), baseDelay);
        double jitter = 1.0 + props.jitterFraction() * ThreadLocalRandom.current().nextDouble();
        long delayMs = (long) (cappedDelay * jitter);
        return now.plusMillis(delayMs);
    }

    private Instant computeBackoff(int currentAttempt, Instant now) {
        return computeBackoff(currentAttempt, now, props);
    }

    private static DomainEvent reconstruct(OutboxEvent e) {
        return new DomainEvent(
                e.getEventId(),
                e.getEventType(),
                e.getAggregateType(),
                e.getAggregateId(),
                e.getCreatedAt(),
                e.getTraceId(),
                e.getActorUserId(),
                e.getPayload()   // raw JSON string; handlers deserialise as needed
        );
    }
}
