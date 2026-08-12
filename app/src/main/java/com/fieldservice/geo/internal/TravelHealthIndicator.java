package com.fieldservice.geo.internal;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator health indicator for the travel-time circuit breaker.
 *
 * <p>Reports {@code UP} when the breaker is CLOSED or HALF_OPEN, and
 * {@code OUT_OF_SERVICE} when OPEN (degraded mode active). Operators can
 * trigger a manual check by hitting {@code /actuator/health/travelProvider}.
 *
 * <h3>Runbook</h3>
 * <ul>
 *   <li><b>OPEN breaker</b>: travel estimates are Haversine-derived; recommendations
 *       are still produced but carry {@code travelEstimateDegraded=true}. The breaker
 *       attempts to move to HALF_OPEN after the configured {@code waitDuration}.</li>
 *   <li><b>HALF_OPEN</b>: probing live traffic; a probe failure re-opens the breaker.</li>
 *   <li><b>Cache recovery</b>: warm cache entries survive a Redis flush for up to the
 *       configured TTL (default 300 s). After a flush, the first request per origin
 *       incurs a live provider call or Haversine fallback.</li>
 * </ul>
 */
class TravelHealthIndicator implements HealthIndicator {

    private final CircuitBreaker circuitBreaker;

    TravelHealthIndicator(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return switch (state) {
            case CLOSED, HALF_OPEN, METRICS_ONLY -> Health.up()
                    .withDetail("circuitBreakerState", state.name())
                    .build();
            case OPEN, FORCED_OPEN -> Health.outOfService()
                    .withDetail("circuitBreakerState", state.name())
                    .withDetail("degradedMode", "Haversine fallback active — recommendations still produced")
                    .build();
            default -> Health.unknown()
                    .withDetail("circuitBreakerState", state.name())
                    .build();
        };
    }
}
