package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.DeliveryOutcome;
import com.fieldservice.notification.api.NotificationPort;
import com.fieldservice.notification.api.NotificationRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Main notification delivery implementation.
 *
 * <p>Delivery flow:
 * <ol>
 *   <li>Idempotency check — returns SENT immediately if a prior SENT attempt exists</li>
 *   <li>Attempt external delivery wrapped in Retry + CircuitBreaker</li>
 *   <li>On success → persist SENT attempt row and return SENT</li>
 *   <li>On CB_OPEN or transient failure → in-app fallback → persist DEGRADED row</li>
 *   <li>On permanent failure → persist PERMANENT_FAILURE row (no fallback)</li>
 *   <li>Emit Micrometer metrics tagged by channel, adapter, outcome</li>
 * </ol>
 *
 * <p>Restricted to the {@code worker} profile; the api tier must not load this bean.
 */
@Profile("worker")
class ResilientNotificationDispatcher implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(ResilientNotificationDispatcher.class);

    private final ExternalNotificationAdapter externalAdapter;
    private final InAppFallbackAdapter inAppAdapter;
    private final DeliveryAttemptRepository attemptRepository;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final NotificationMetrics metrics;

    ResilientNotificationDispatcher(ExternalNotificationAdapter externalAdapter,
                                     InAppFallbackAdapter inAppAdapter,
                                     DeliveryAttemptRepository attemptRepository,
                                     CircuitBreaker circuitBreaker,
                                     Retry retry,
                                     NotificationMetrics metrics) {
        this.externalAdapter = externalAdapter;
        this.inAppAdapter = inAppAdapter;
        this.attemptRepository = attemptRepository;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.metrics = metrics;
    }

    @Override
    public DeliveryOutcome send(NotificationRequest request) {
        String masked = RecipientMask.mask(request.recipientContact());

        // Idempotency: skip if already successfully delivered
        if (attemptRepository.existsByEventIdAndChannelAndRecipientUserIdAndOutcome(
                request.eventId(), request.channel(), request.recipientUserId(), DeliveryOutcome.SENT)) {
            log.debug("notification_idempotent skip eventId={} channel={}", request.eventId(), request.channel());
            return DeliveryOutcome.SENT;
        }

        int nextAttempt = attemptRepository.maxAttemptNo(
                request.eventId(), request.channel(), request.recipientUserId()) + 1;

        Instant start = Instant.now();

        // IN_APP channel bypasses external adapter entirely
        if (request.channel() == com.fieldservice.notification.api.NotificationChannel.IN_APP) {
            return fallbackAndRecord(request, masked, nextAttempt, "in_app", "IN_APP_CHANNEL", start);
        }

        try {
            Supplier<String> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, () -> {
                        try {
                            return externalAdapter.send(request, masked);
                        } catch (Exception e) {
                            if (e instanceof ProviderPermanentException) throw (ProviderPermanentException) e;
                            if (e instanceof RuntimeException) throw (RuntimeException) e;
                            throw new ProviderTransientException("io_error", e);
                        }
                    }));

            String providerRef = decorated.get();
            Duration elapsed = Duration.between(start, Instant.now());

            persistAttempt(request, masked, externalAdapter.adapterName(), nextAttempt,
                    DeliveryOutcome.SENT, providerRef, (int) elapsed.toMillis(), null);
            metrics.recordSend(request.channel().name(), externalAdapter.adapterName(), "SENT", elapsed);

            log.info("notification_sent eventId={} channel={} adapter={} ref={}",
                    request.eventId(), request.channel(), externalAdapter.adapterName(), providerRef);
            return DeliveryOutcome.SENT;

        } catch (CallNotPermittedException e) {
            log.warn("notification_circuit_open eventId={} channel={}", request.eventId(), request.channel());
            return fallbackAndRecord(request, masked, nextAttempt, externalAdapter.adapterName(), "CB_OPEN", start);

        } catch (ProviderPermanentException e) {
            Duration elapsed = Duration.between(start, Instant.now());
            persistAttempt(request, masked, externalAdapter.adapterName(), nextAttempt,
                    DeliveryOutcome.PERMANENT_FAILURE, null, (int) elapsed.toMillis(), "PERMANENT");
            metrics.recordSend(request.channel().name(), externalAdapter.adapterName(), "PERMANENT_FAILURE", elapsed);
            log.warn("notification_permanent_failure eventId={} channel={}", request.eventId(), request.channel());
            return DeliveryOutcome.PERMANENT_FAILURE;

        } catch (Exception e) {
            log.warn("notification_transient_failure eventId={} channel={} error={}",
                    request.eventId(), request.channel(), e.getClass().getSimpleName());
            return fallbackAndRecord(request, masked, nextAttempt, externalAdapter.adapterName(),
                    e.getClass().getSimpleName(), start);
        }
    }

    private DeliveryOutcome fallbackAndRecord(NotificationRequest request, String masked,
                                               int attemptNo, String adapter,
                                               String failureCode, Instant start) {
        inAppAdapter.store(request);
        Duration elapsed = Duration.between(start, Instant.now());
        persistAttempt(request, masked, adapter, attemptNo,
                DeliveryOutcome.DEGRADED, null, (int) elapsed.toMillis(), failureCode);
        metrics.recordSend(request.channel().name(), adapter, "DEGRADED", elapsed);
        return DeliveryOutcome.DEGRADED;
    }

    private void persistAttempt(NotificationRequest request, String masked, String adapter,
                                  int attemptNo, DeliveryOutcome outcome,
                                  String providerRef, int durationMs, String failureCode) {
        try {
            attemptRepository.save(DeliveryAttemptEntity.create(
                    request.eventId(), request.channel(), adapter,
                    request.recipientUserId(), masked, attemptNo,
                    outcome, providerRef, durationMs, failureCode));
        } catch (Exception e) {
            log.error("notification_attempt_persist_failed eventId={} error={}", request.eventId(), e.getMessage());
        }
    }
}
