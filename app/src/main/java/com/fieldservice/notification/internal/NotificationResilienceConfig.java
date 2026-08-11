package com.fieldservice.notification.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Random;

/** Builds Resilience4j components for the notification delivery port. Package-private. */
class NotificationResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationResilienceConfig.class);
    private static final Random JITTER = new Random();

    static CircuitBreaker circuitBreaker() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(20)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        CircuitBreaker cb = CircuitBreaker.of("notification-provider", cfg);
        cb.getEventPublisher().onStateTransition(e ->
                log.warn("notification_circuit_breaker_transition name={} from={} to={}",
                        e.getCircuitBreakerName(),
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
        return cb;
    }

    static Retry retry() {
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(3)
                .retryOnException(t -> t instanceof RetryableNotificationException)
                .intervalFunction(attempt -> {
                    long base = 100L * (1L << (attempt - 1));
                    long jitter = (long) (JITTER.nextDouble() * base);
                    return base + jitter;
                })
                .build();
        return Retry.of("notification-provider", cfg);
    }
}
