package com.fieldservice.geo.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Actuator health indicator exposing the travel-time circuit-breaker state.
 *
 * <p>Runbook: when this indicator reports DOWN, the geo adapter is in fallback
 * mode — all travel estimates are Haversine-derived and marked degraded.
 * The breaker reopens automatically after the configured wait duration (default 30 s).
 * A circuit-open alert ({@code geo.travel.circuit-breaker}) means the travel provider
 * is returning errors or timing out; check provider status and review
 * {@code geo.travel.call.errors} counter for the failure reason.
 * Cache-warm expectations: after a Redis flush the first N calls will hit the provider;
 * if the provider is healthy the breaker will close within one sliding window (20 calls).
 */
@Component
@Profile("!test")
class TravelBreakerHealthIndicator implements HealthIndicator {

    private final CircuitBreaker circuitBreaker;

    TravelBreakerHealthIndicator(CircuitBreaker travelCircuitBreaker) {
        this.circuitBreaker = travelCircuitBreaker;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return switch (state) {
            case CLOSED, HALF_OPEN, METRICS_ONLY, DISABLED ->
                    Health.up().withDetail("circuitBreaker", state.name()).build();
            case OPEN, FORCED_OPEN ->
                    Health.down().withDetail("circuitBreaker", state.name())
                            .withDetail("note", "travel estimates degraded to Haversine fallback").build();
        };
    }
}
