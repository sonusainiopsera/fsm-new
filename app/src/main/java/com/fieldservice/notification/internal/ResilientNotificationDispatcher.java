package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.adapter.ExternalNotificationAdapter;
import com.fieldservice.notification.internal.adapter.InAppFallbackAdapter;
import com.fieldservice.platform.util.UuidV7;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

import java.util.function.Supplier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single implementation of {@link NotificationPort}. Restricted to the {@code worker} profile
 * so no provider I/O ever executes on an api request thread.
 *
 * <p>Execution order per dispatch:
 * <ol>
 *   <li>Idempotency check — skip if a terminal (SENT/DEGRADED) row already exists.</li>
 *   <li>Attempt external delivery via Resilience4j Retry wrapped in Circuit Breaker.</li>
 *   <li>On success — persist SENT attempt row, return SENT.</li>
 *   <li>On MaxRetriesExceededException, CallNotPermittedException, or PermanentNotificationException
 *       — durably write an in-app notification row (REQUIRES_NEW transaction), then persist
 *       DEGRADED attempt row and return DEGRADED.</li>
 *   <li>If the fallback itself fails — persist PERMANENT_FAILURE row, re-throw so the
 *       message consumer does not acknowledge and the alert is not silently lost.</li>
 * </ol>
 */
@Service
@Profile("worker")
class ResilientNotificationDispatcher implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(ResilientNotificationDispatcher.class);

    private final ExternalNotificationAdapter externalAdapter;
    private final InAppFallbackAdapter        fallbackAdapter;
    private final NotificationDeliveryAttemptRepository attemptRepo;
    private final CircuitBreaker  circuitBreaker;
    private final Retry           retry;
    private final MeterRegistry   meterRegistry;

    ResilientNotificationDispatcher(
            ExternalNotificationAdapter externalAdapter,
            InAppFallbackAdapter        fallbackAdapter,
            NotificationDeliveryAttemptRepository attemptRepo,
            MeterRegistry               meterRegistry) {
        this.externalAdapter = externalAdapter;
        this.fallbackAdapter = fallbackAdapter;
        this.attemptRepo     = attemptRepo;
        this.meterRegistry   = meterRegistry;
        this.circuitBreaker  = NotificationResilienceConfig.circuitBreaker();
        this.retry           = NotificationResilienceConfig.retry();

        registerBreakerGauge();
    }

    @Override
    @Transactional
    public DeliveryOutcome send(NotificationRequest request) {
        // --- idempotency: skip duplicate terminal outcomes ---
        Optional<NotificationDeliveryAttemptEntity> existing =
                attemptRepo.findByEventIdAndChannelAndRecipientUserId(
                        request.eventId(), request.channel(), request.recipientUserId());
        if (existing.isPresent()) {
            DeliveryOutcome cached = existing.get().getOutcome();
            if (cached == DeliveryOutcome.SENT || cached == DeliveryOutcome.DEGRADED) {
                log.info("notification_idempotent_skip eventId={} channel={} outcome={}",
                        request.eventId(), request.channel(), cached);
                return cached;
            }
        }

        String recipientMask = RecipientMask.mask(request.recipientContact());
        Instant start = Instant.now();
        AtomicInteger attemptCount = new AtomicInteger(0);

        try {
            // CB outer, Retry inner: CB events track complete dispatch operations, not individual HTTP calls.
            Supplier<String> base = () -> {
                attemptCount.incrementAndGet();
                return externalAdapter.send(request);
            };
            Supplier<String> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, base));
            String providerRef = decorated.get();

            int durationMs = (int) Duration.between(start, Instant.now()).toMillis();
            persistAttempt(request, externalAdapter.adapterName(), recipientMask,
                    attemptCount.get(), DeliveryOutcome.SENT, providerRef, durationMs, null);
            recordMetric(request, externalAdapter.adapterName(), DeliveryOutcome.SENT, durationMs);
            return DeliveryOutcome.SENT;

        } catch (CallNotPermittedException e) {
            log.warn("notification_breaker_open eventId={} channel={} mask={}",
                    request.eventId(), request.channel(), recipientMask);
            return degradeFallback(request, recipientMask, start, "BREAKER_OPEN",
                    attemptCount.get());

        } catch (MaxRetriesExceededException e) {
            String failureCode = extractFailureCode(e.getCause());
            log.warn("notification_retries_exhausted eventId={} channel={} mask={} code={}",
                    request.eventId(), request.channel(), recipientMask, failureCode);
            return degradeFallback(request, recipientMask, start, failureCode, attemptCount.get());

        } catch (PermanentNotificationException e) {
            log.warn("notification_permanent_failure eventId={} channel={} mask={} code={}",
                    request.eventId(), request.channel(), recipientMask, e.getFailureCode());
            return degradeFallback(request, recipientMask, start, e.getFailureCode(),
                    attemptCount.get());

        } catch (Exception e) {
            log.error("notification_unexpected_error eventId={} channel={} mask={}",
                    request.eventId(), request.channel(), recipientMask, e);
            return degradeFallback(request, recipientMask, start, "UNEXPECTED", attemptCount.get());
        }
    }

    private DeliveryOutcome degradeFallback(NotificationRequest request, String recipientMask,
                                             Instant start, String failureCode, int attempts) {
        try {
            UUID inAppId = fallbackAdapter.persist(request);
            fallbackAdapter.pushSse(request.recipientUserId(), inAppId, request);

            int durationMs = (int) Duration.between(start, Instant.now()).toMillis();
            persistAttempt(request, "IN_APP_FALLBACK", recipientMask,
                    attempts + 1, DeliveryOutcome.DEGRADED, null, durationMs, failureCode);
            recordMetric(request, "IN_APP_FALLBACK", DeliveryOutcome.DEGRADED, durationMs);
            return DeliveryOutcome.DEGRADED;

        } catch (Exception fallbackEx) {
            log.error("notification_fallback_failed eventId={} channel={} mask={}",
                    request.eventId(), request.channel(), recipientMask, fallbackEx);
            int durationMs = (int) Duration.between(start, Instant.now()).toMillis();
            persistAttemptQuietly(request, "IN_APP_FALLBACK", recipientMask,
                    attempts + 1, DeliveryOutcome.PERMANENT_FAILURE, durationMs, "FALLBACK_FAILED");
            recordMetric(request, "IN_APP_FALLBACK", DeliveryOutcome.PERMANENT_FAILURE, durationMs);
            throw new IllegalStateException(
                    "notification fallback failed for eventId=" + request.eventId(), fallbackEx);
        }
    }

    private void persistAttempt(NotificationRequest req, String adapter, String mask,
                                 int attemptNo, DeliveryOutcome outcome, String providerRef,
                                 int durationMs, String failureCode) {
        var entity = NotificationDeliveryAttemptEntity.of(
                UuidV7.generate(), req.eventId(), req.channel(), adapter,
                req.recipientUserId(), mask, attemptNo, outcome,
                providerRef, durationMs, failureCode);
        attemptRepo.save(entity);
    }

    private void persistAttemptQuietly(NotificationRequest req, String adapter, String mask,
                                        int attemptNo, DeliveryOutcome outcome,
                                        int durationMs, String failureCode) {
        try {
            persistAttempt(req, adapter, mask, attemptNo, outcome, null, durationMs, failureCode);
        } catch (Exception e) {
            log.error("notification_attempt_persist_failed eventId={}", req.eventId(), e);
        }
    }

    private void recordMetric(NotificationRequest req, String adapter,
                               DeliveryOutcome outcome, int durationMs) {
        Counter.builder("notification_send_total")
                .tag("channel", req.channel().name())
                .tag("adapter", adapter)
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .increment();
        Timer.builder("notification_send_duration_seconds")
                .tag("channel", req.channel().name())
                .tag("adapter", adapter)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    private void registerBreakerGauge() {
        meterRegistry.gauge("notification_breaker_state",
                circuitBreaker,
                cb -> switch (cb.getState()) {
                    case CLOSED      -> 0.0;
                    case HALF_OPEN   -> 0.5;
                    case OPEN        -> 1.0;
                    default          -> -1.0;
                });
    }

    private static String extractFailureCode(Throwable cause) {
        if (cause instanceof RetryableNotificationException rne) {
            return rne.getFailureCode();
        }
        return "UNKNOWN";
    }
}
