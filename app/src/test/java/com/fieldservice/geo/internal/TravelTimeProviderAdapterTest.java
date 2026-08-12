package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WireMock integration tests for {@link TravelTimeProviderAdapter}.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Success — all entries resolved from provider.</li>
 *   <li>HTTP 500 — all entries fall back to Haversine, marked degraded.</li>
 *   <li>Slow response exceeding TimeLimiter — fallback.</li>
 *   <li>Malformed body (missing {@code rows}) — fallback.</li>
 *   <li>Partial matrix — missing entries fall back individually.</li>
 * </ol>
 */
@WireMockTest
@DisplayName("TravelTimeProviderAdapter WireMock integration tests")
class TravelTimeProviderAdapterTest {

    private TravelTimeProviderAdapter adapter;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static final TravelCoordinate ORIGIN_1 = new TravelCoordinate(51.500, -0.100);
    private static final TravelCoordinate ORIGIN_2 = new TravelCoordinate(51.510, -0.110);
    private static final TravelCoordinate ORIGIN_3 = new TravelCoordinate(51.520, -0.120);
    private static final TravelCoordinate DESTINATION = new TravelCoordinate(51.600, -0.200);

    @BeforeEach
    void buildAdapter(WireMockRuntimeInfo wm) {
        String baseUrl = wm.getHttpBaseUrl();

        // No-op allow-list — SSRF is validated separately; WireMock uses localhost
        TravelTimeEgressAllowList noOpAllowList = new TravelTimeEgressAllowList(
                List.of("localhost", "127.0.0.1")) {
            @Override
            void validate(String url) { /* skip private-IP check for localhost in test */ }
        };

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        CircuitBreaker cb = CircuitBreaker.of("test-cb",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50).slidingWindowSize(10)
                        .waitDurationInOpenState(Duration.ofMillis(500))
                        .build());

        // Short timeout so the slow-response test doesn't take the full provider read timeout
        TimeLimiter tl = TimeLimiter.of("test-tl",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(500))
                        .cancelRunningFuture(true).build());

        Retry retry = Retry.of("test-retry",
                RetryConfig.custom().maxAttempts(1).build());

        GeoMetrics metrics = new GeoMetrics(meterRegistry);
        HaversineEstimator haversine = new HaversineEstimator(30.0);

        adapter = new TravelTimeProviderAdapter(
                restClient, baseUrl, "test-api-key",
                new NoOpTravelCacheGateway(),
                haversine, noOpAllowList,
                cb, tl, retry,
                Executors.newVirtualThreadPerTaskExecutor(),
                metrics);
    }

    // ── Scenario 1: success ────────────────────────────────────────────────────

    @Test
    @DisplayName("Provider 200 OK — all entries resolved, none degraded")
    void success_allEntriesResolvedFromProvider() {
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(okJson("""
                        {
                          "rows": [
                            {"originIndex": 0, "estimatedMinutes": 12.5},
                            {"originIndex": 1, "estimatedMinutes": 25.0},
                            {"originIndex": 2, "estimatedMinutes": 8.3}
                          ]
                        }
                        """)));

        TravelMatrixResult result = adapter.estimateTravelTime(
                List.of(ORIGIN_1, ORIGIN_2, ORIGIN_3), DESTINATION);

        assertThat(result.anyDegraded()).isFalse();
        assertThat(result.entries()).hasSize(3);
        assertThat(result.entries().get(0).estimatedMinutes()).isEqualTo(12.5);
        assertThat(result.entries().get(1).estimatedMinutes()).isEqualTo(25.0);
        assertThat(result.entries().get(2).estimatedMinutes()).isEqualTo(8.3);
    }

    @Test
    @DisplayName("Exactly one HTTP call is issued regardless of candidate count (AC-2)")
    void exactlyOneHttpCallForMultipleOrigins() {
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(okJson("""
                        {
                          "rows": [
                            {"originIndex": 0, "estimatedMinutes": 10.0},
                            {"originIndex": 1, "estimatedMinutes": 20.0}
                          ]
                        }
                        """)));

        adapter.estimateTravelTime(List.of(ORIGIN_1, ORIGIN_2), DESTINATION);

        verify(1, postRequestedFor(urlEqualTo("/matrix")));
    }

    // ── Scenario 2: HTTP 500 ───────────────────────────────────────────────────

    @Test
    @DisplayName("Provider 500 — all entries degraded to Haversine fallback, no exception")
    void provider500_allEntriesDegraded() {
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"internal\"}")));

        TravelMatrixResult result = adapter.estimateTravelTime(
                List.of(ORIGIN_1, ORIGIN_2), DESTINATION);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries()).allMatch(e -> e.degraded());
        assertThat(result.entries()).allMatch(e -> e.estimatedMinutes() >= 0);
    }

    // ── Scenario 3: slow response (timeout) ───────────────────────────────────

    @Test
    @DisplayName("Provider slow response exceeds TimeLimiter — degraded fallback, no exception")
    void slowResponse_exceedsTimeLimiter_degraded() {
        // WireMock delays 2 s; TimeLimiter in setUp is 500 ms
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(aResponse()
                        .withFixedDelay(2000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"rows\":[{\"originIndex\":0,\"estimatedMinutes\":10.0}]}")));

        TravelMatrixResult result = adapter.estimateTravelTime(
                List.of(ORIGIN_1), DESTINATION);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries().get(0).degraded()).isTrue();
    }

    // ── Scenario 4: malformed body ─────────────────────────────────────────────

    @Test
    @DisplayName("Provider returns malformed body (no rows field) — degraded fallback")
    void malformedBody_degraded() {
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(okJson("""
                        {"error": "upstream_provider_error", "message": "internal error"}
                        """)));

        TravelMatrixResult result = adapter.estimateTravelTime(
                List.of(ORIGIN_1), DESTINATION);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries().get(0).degraded()).isTrue();
    }

    // ── Scenario 5: partial matrix ─────────────────────────────────────────────

    @Test
    @DisplayName("Provider returns fewer rows than origins — missing entries use Haversine")
    void partialMatrix_missingEntriesDegraded() {
        // 3 origins requested, only 1 row returned
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(okJson("""
                        {
                          "rows": [
                            {"originIndex": 0, "estimatedMinutes": 15.0}
                          ]
                        }
                        """)));

        TravelMatrixResult result = adapter.estimateTravelTime(
                List.of(ORIGIN_1, ORIGIN_2, ORIGIN_3), DESTINATION);

        assertThat(result.entries()).hasSize(3);
        // First entry resolved from provider
        assertThat(result.entries().get(0).estimatedMinutes()).isEqualTo(15.0);
        assertThat(result.entries().get(0).degraded()).isFalse();
        // Entries 1 and 2 use Haversine fallback
        assertThat(result.entries().get(1).degraded()).isTrue();
        assertThat(result.entries().get(2).degraded()).isTrue();
        assertThat(result.anyDegraded()).isTrue();
    }

    // ── Circuit breaker opens after repeated failures ──────────────────────────

    @Test
    @DisplayName("CircuitBreaker opens after repeated failures — subsequent calls short-circuit to fallback")
    void circuitBreakerOpens_shortCircuitsToFallback() {
        // Drive 10 failures to trip the 10-call window CB
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 12; i++) {
            adapter.estimateTravelTime(List.of(ORIGIN_1), DESTINATION);
        }

        // After the breaker opens, no further HTTP calls should be made
        resetAllRequests();
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(aResponse().withStatus(200).withBody("{\"rows\":[]}")));

        TravelMatrixResult result = adapter.estimateTravelTime(List.of(ORIGIN_1), DESTINATION);

        assertThat(result.anyDegraded()).isTrue();
        verify(0, postRequestedFor(urlEqualTo("/matrix")));
    }

    // ── Metrics ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("geo.travel.call.duration timer is recorded on success")
    void metrics_callDurationRecorded() {
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(okJson("{\"rows\":[{\"originIndex\":0,\"estimatedMinutes\":5.0}]}")));

        adapter.estimateTravelTime(List.of(ORIGIN_1), DESTINATION);

        var timer = meterRegistry.find(GeoMetrics.CALL_DURATION)
                .tag("provider", TravelTimeProviderAdapter.PROVIDER)
                .tag("outcome", "success")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("geo.travel.degraded counter increments on fallback")
    void metrics_degradedCounterIncrements() {
        stubFor(post(urlEqualTo("/matrix"))
                .willReturn(aResponse().withStatus(500)));

        adapter.estimateTravelTime(List.of(ORIGIN_1, ORIGIN_2), DESTINATION);

        var counter = meterRegistry.find(GeoMetrics.DEGRADED)
                .tag("provider", TravelTimeProviderAdapter.PROVIDER)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(2.0);
    }
}
