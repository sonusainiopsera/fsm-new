package com.fieldservice.aigateway.internal;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.io.IOException;
import java.time.Duration;
import java.util.Random;

/**
 * Programmatic Resilience4j configuration for the AI gateway.
 *
 * <p>Defaults (all overridable via {@link AiGatewayProperties}):
 * <ul>
 *   <li>TimeLimiter: 10 s — enforced via {@link java.util.concurrent.Future#get(long, java.util.concurrent.TimeUnit)}
 *       in the adapter, not through Resilience4j's TimeLimiter (which requires ScheduledExecutor).</li>
 *   <li>CircuitBreaker: 50% failure rate, 20-call window, 30 s open, 3 half-open calls.</li>
 *   <li>Bulkhead: 16 max concurrent calls (semaphore).</li>
 *   <li>Retry: 1 retry, jittered backoff, idempotent connection-level errors only.</li>
 * </ul>
 */
class AiGatewayResilienceConfig {

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final long timeLimitMs;

    AiGatewayResilienceConfig(AiGatewayProperties props) {
        AiGatewayProperties.Resilience r = props.getResilience();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(r.getFailureRateThreshold())
                .slidingWindowSize(r.getSlidingWindowSize())
                .waitDurationInOpenState(r.getWaitDurationOpen())
                .permittedNumberOfCallsInHalfOpenState(r.getHalfOpenCalls())
                .recordExceptions(Exception.class)
                .build();
        this.circuitBreaker = CircuitBreaker.of("ai-gateway", cbConfig);

        // Log circuit-breaker state transitions at WARN
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> org.slf4j.LoggerFactory
                        .getLogger(AiGatewayResilienceConfig.class)
                        .warn("AI gateway circuit-breaker transition: {}", event.getStateTransition()));

        BulkheadConfig bConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(r.getMaxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build();
        this.bulkhead = Bulkhead.of("ai-gateway", bConfig);

        // One retry on connection-level errors only; jittered wait 0–500 ms
        Random jitter = new Random();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2) // 1 original + 1 retry
                .retryExceptions(IOException.class)
                .waitDuration(Duration.ofMillis(250 + jitter.nextInt(250)))
                .build();
        this.retry = Retry.of("ai-gateway", retryConfig);

        this.timeLimitMs = r.getTimeLimitDuration().toMillis();
    }

    CircuitBreaker circuitBreaker() { return circuitBreaker; }
    Bulkhead bulkhead() { return bulkhead; }
    Retry retry() { return retry; }
    long timeLimitMs() { return timeLimitMs; }
}
