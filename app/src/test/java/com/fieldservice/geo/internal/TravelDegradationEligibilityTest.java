package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import com.fieldservice.geo.api.TravelMatrixEntry;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative test: provider degradation must never change the eligible candidate set (AC-12).
 *
 * <p>When the travel provider is fully unavailable, all entries are returned with
 * Haversine estimates and the degraded flag. The result MUST still contain one entry
 * per requested origin — no origins are dropped or added.
 */
@DisplayName("Travel degradation must not alter eligible candidate set (AC-12)")
class TravelDegradationEligibilityTest {

    private static final TravelCoordinate DEST = new TravelCoordinate(51.600, -0.200);

    private TravelTimePort buildFullyDegradedAdapter() {
        TravelTimeEgressAllowList noOpAllowList = new TravelTimeEgressAllowList(List.of()) {
            @Override
            void validate(String url) {} // allow-list passes so SSRF doesn't fail startup
        };

        // No real HTTP client needed — we force immediate timeout
        TimeLimiter tl = TimeLimiter.of("deg-tl",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(1))
                        .cancelRunningFuture(true).build());

        // Circuit breaker already open — short-circuits to fallback immediately
        CircuitBreaker cb = CircuitBreaker.ofDefaults("deg-cb");
        cb.transitionToOpenState();

        return new TravelTimeProviderAdapter(
                RestClient.create(),
                "http://unreachable.local",
                "no-key",
                new NoOpTravelCacheGateway(),
                new HaversineEstimator(30.0),
                noOpAllowList,
                cb, tl, Retry.ofDefaults("deg-retry"),
                Executors.newVirtualThreadPerTaskExecutor(),
                new GeoMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("Full provider outage returns one entry per origin — no origins dropped")
    void fullOutage_returnsOneEntryPerOrigin() {
        List<TravelCoordinate> origins = List.of(
                new TravelCoordinate(51.501, -0.101),
                new TravelCoordinate(51.511, -0.111),
                new TravelCoordinate(51.521, -0.121),
                new TravelCoordinate(51.531, -0.131),
                new TravelCoordinate(51.541, -0.141));

        TravelTimePort port = buildFullyDegradedAdapter();
        TravelMatrixResult result = port.estimateTravelTime(origins, DEST);

        // The number of results equals the number of requested origins — no candidate is dropped
        assertThat(result.entries()).hasSize(origins.size());
    }

    @Test
    @DisplayName("Full provider outage: all entries are degraded, none are null")
    void fullOutage_allEntriesDegradedNotNull() {
        List<TravelCoordinate> origins = List.of(
                new TravelCoordinate(51.502, -0.102),
                new TravelCoordinate(51.512, -0.112));

        TravelTimePort port = buildFullyDegradedAdapter();
        TravelMatrixResult result = port.estimateTravelTime(origins, DEST);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries()).noneMatch(e -> e == null);
        assertThat(result.entries()).allMatch(TravelMatrixEntry::degraded);
    }

    @Test
    @DisplayName("Full provider outage: estimates are non-negative (Haversine produces valid distances)")
    void fullOutage_estimatesNonNegative() {
        List<TravelCoordinate> origins = List.of(
                new TravelCoordinate(51.503, -0.103));

        TravelTimePort port = buildFullyDegradedAdapter();
        TravelMatrixResult result = port.estimateTravelTime(origins, DEST);

        assertThat(result.entries()).allMatch(e -> e.estimatedMinutes() >= 0);
    }

    @Test
    @DisplayName("Full provider outage: no exception escapes to the caller")
    void fullOutage_noExceptionEscapes() {
        List<TravelCoordinate> origins = List.of(
                new TravelCoordinate(51.504, -0.104));

        TravelTimePort port = buildFullyDegradedAdapter();

        // This must not throw — any exception here would be a contract violation
        TravelMatrixResult result = port.estimateTravelTime(origins, DEST);
        assertThat(result).isNotNull();
    }
}
