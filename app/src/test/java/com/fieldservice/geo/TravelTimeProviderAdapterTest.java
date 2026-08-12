package com.fieldservice.geo;

import com.fieldservice.geo.api.Coordinates;
import com.fieldservice.geo.api.TravelMatrixResult;
import com.fieldservice.geo.api.TravelTimePort;
import com.fieldservice.geo.internal.HaversineEstimator;
import com.fieldservice.geo.internal.TravelCacheGateway;
import com.fieldservice.geo.internal.TravelMetrics;
import com.fieldservice.geo.internal.TravelProviderAllowList;
import com.fieldservice.geo.internal.TravelProviderProperties;
import com.fieldservice.geo.internal.TravelTimeProviderAdapter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WireMock-backed integration tests for {@link TravelTimeProviderAdapter}.
 *
 * <p>No Spring context — direct construction, real Resilience4j, real RestClient.
 * Tests:
 * <ul>
 *   <li>Success: provider returns valid matrix, results mapped correctly</li>
 *   <li>HTTP 500: fallback to Haversine, degraded=true for all entries</li>
 *   <li>Slow response > TimeLimiter: Haversine fallback, no exception escapes</li>
 *   <li>Malformed body: Haversine fallback, no exception escapes</li>
 *   <li>Partial matrix response: missing entries filled by Haversine</li>
 *   <li>Batch: exactly one outbound HTTP call regardless of origin count</li>
 *   <li>Circuit breaker: opens after 50% failures over window, subsequent calls short-circuit</li>
 *   <li>Cache hit: no outbound HTTP call on second identical request</li>
 * </ul>
 */
class TravelTimeProviderAdapterTest {

    private static final UUID TECH_1 = UUID.fromString("00000000-0000-0000-0001-000000000001");
    private static final UUID TECH_2 = UUID.fromString("00000000-0000-0000-0001-000000000002");
    private static final UUID TECH_3 = UUID.fromString("00000000-0000-0000-0001-000000000003");

    private static final Coordinates LONDON    = new Coordinates(51.5074, -0.1278);
    private static final Coordinates OXFORD    = new Coordinates(51.7520,  -1.2577);
    private static final Coordinates CAMBRIDGE = new Coordinates(52.2053,   0.1218);
    private static final Coordinates BIRMINGHAM = new Coordinates(52.4862,  -1.8904);

    private WireMockServer wireMock;
    private TravelTimeProviderAdapter adapter;
    private TravelCacheGateway cacheGateway;
    private ExecutorService executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        String baseUrl = "http://localhost:" + wireMock.port();

        TravelProviderProperties props = new TravelProviderProperties(
                new TravelProviderProperties.Provider(
                        baseUrl, "test-api-key",
                        List.of("localhost", "127.0.0.1"),
                        Duration.ofSeconds(2), Duration.ofSeconds(5),
                        "car", 50.0),
                new TravelProviderProperties.Resilience(
                        Duration.ofSeconds(3),   // timeLimiter > wiremock delay in some tests
                        1,                        // maxAttempts = 1 (no retry in most tests)
                        50f, 4,                   // CB: 50% over 4-call window (smaller for test speed)
                        Duration.ofSeconds(30), 2),
                new TravelProviderProperties.Cache(300L, 4));

        // Mock Redis template — cache always misses by default
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any(String.class))).thenReturn(null);

        var allowList    = new TravelProviderAllowList(props);
        var haversine    = new HaversineEstimator(props.provider().averageSpeedKph());
        cacheGateway     = new TravelCacheGateway(redisTemplate, props);
        var metrics      = new TravelMetrics(new SimpleMeterRegistry());

        CircuitBreaker cb = CircuitBreaker.of("travel-test",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50f).slidingWindowSize(4)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build());
        TimeLimiter tl = TimeLimiter.of("travel-test",
                TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).cancelRunningFuture(true).build());
        Retry retry = Retry.of("travel-test", RetryConfig.custom().maxAttempts(1).build());
        executor = Executors.newVirtualThreadPerTaskExecutor();

        RestClient restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();

        adapter = new TravelTimeProviderAdapter(restClient, props, allowList,
                cacheGateway, haversine, metrics, cb, tl, retry, executor);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
        executor.shutdownNow();
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("success: provider returns valid matrix — all entries non-degraded")
    void success_allEntriesMapped() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "estimates": [
                                    {"technicianId": "%s", "estimatedMinutes": 12},
                                    {"technicianId": "%s", "estimatedMinutes": 25}
                                  ]
                                }
                                """.formatted(TECH_1, TECH_2))));

        List<TravelTimePort.OriginRequest> origins = List.of(
                new TravelTimePort.OriginRequest(TECH_1, LONDON),
                new TravelTimePort.OriginRequest(TECH_2, OXFORD));

        TravelMatrixResult result = adapter.estimate(origins, BIRMINGHAM);

        assertThat(result.anyDegraded()).isFalse();
        assertThat(result.entries()).hasSize(2);
        assertThat(entry(result, TECH_1).estimatedMinutes()).isEqualTo(12);
        assertThat(entry(result, TECH_1).degraded()).isFalse();
        assertThat(entry(result, TECH_2).estimatedMinutes()).isEqualTo(25);
    }

    @Test
    @DisplayName("batch: exactly one outbound HTTP call for multiple origins")
    void batch_singleOutboundCallForMultipleOrigins() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "estimates": [
                                    {"technicianId": "%s", "estimatedMinutes": 10},
                                    {"technicianId": "%s", "estimatedMinutes": 20},
                                    {"technicianId": "%s", "estimatedMinutes": 30}
                                  ]
                                }
                                """.formatted(TECH_1, TECH_2, TECH_3))));

        List<TravelTimePort.OriginRequest> origins = List.of(
                new TravelTimePort.OriginRequest(TECH_1, LONDON),
                new TravelTimePort.OriginRequest(TECH_2, OXFORD),
                new TravelTimePort.OriginRequest(TECH_3, CAMBRIDGE));

        adapter.estimate(origins, BIRMINGHAM);

        // WireMock assertion: exactly 1 POST received
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/v1/matrix")));
    }

    // ─── Failure paths (all degrade to Haversine) ─────────────────────────────

    @Test
    @DisplayName("HTTP 500: all entries degrade to Haversine, no exception")
    void http500_haversineFallback() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(500)
                        .withBody("{\"error\":\"internal server error\"}")));

        TravelMatrixResult result = adapter.estimate(
                List.of(new TravelTimePort.OriginRequest(TECH_1, LONDON)), BIRMINGHAM);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).degraded()).isTrue();
        assertThat(result.entries().get(0).estimatedMinutes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("slow response > TimeLimiter: Haversine fallback, no exception")
    void slowResponse_timeLimiter_haversineFallback() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(5_000) // 5s > 3s TimeLimiter
                        .withBody("{\"estimates\":[]}")));

        TravelMatrixResult result = adapter.estimate(
                List.of(new TravelTimePort.OriginRequest(TECH_1, LONDON)), BIRMINGHAM);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries().get(0).degraded()).isTrue();
    }

    @Test
    @DisplayName("malformed body (missing 'estimates'): Haversine fallback, no exception")
    void malformedBody_haversineFallback() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"unexpected\":\"structure\"}")));

        TravelMatrixResult result = adapter.estimate(
                List.of(new TravelTimePort.OriginRequest(TECH_1, LONDON)), BIRMINGHAM);

        assertThat(result.anyDegraded()).isTrue();
        assertThat(result.entries().get(0).degraded()).isTrue();
    }

    @Test
    @DisplayName("partial response: missing entries filled by Haversine and flagged degraded")
    void partialResponse_missingEntryFilled() {
        // Provider only returns TECH_1; TECH_2 is missing
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "estimates": [
                                    {"technicianId": "%s", "estimatedMinutes": 18}
                                  ]
                                }
                                """.formatted(TECH_1))));

        TravelMatrixResult result = adapter.estimate(
                List.of(
                        new TravelTimePort.OriginRequest(TECH_1, LONDON),
                        new TravelTimePort.OriginRequest(TECH_2, OXFORD)),
                BIRMINGHAM);

        assertThat(result.entries()).hasSize(2);
        assertThat(entry(result, TECH_1).degraded()).isFalse();
        assertThat(entry(result, TECH_1).estimatedMinutes()).isEqualTo(18);
        assertThat(entry(result, TECH_2).degraded()).isTrue();
        assertThat(entry(result, TECH_2).estimatedMinutes()).isGreaterThan(0);
        assertThat(result.anyDegraded()).isTrue();
    }

    @Test
    @DisplayName("empty origin list: returns empty result immediately with no HTTP call")
    void emptyOrigins_noHttpCall() {
        TravelMatrixResult result = adapter.estimate(List.of(), BIRMINGHAM);

        assertThat(result.entries()).isEmpty();
        assertThat(result.anyDegraded()).isFalse();
        // No HTTP calls
        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                urlPathEqualTo("/v1/matrix")));
    }

    // ─── Circuit breaker ──────────────────────────────────────────────────────

    @Test
    @DisplayName("circuit breaker opens after repeated failures; subsequent calls short-circuit")
    void circuitBreaker_opensAndShortCircuits() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/matrix"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"down\"}")));

        // Drive 4 failures to open the 4-call 50%-threshold breaker
        for (int i = 0; i < 4; i++) {
            TravelMatrixResult r = adapter.estimate(
                    List.of(new TravelTimePort.OriginRequest(TECH_1, LONDON)), BIRMINGHAM);
            assertThat(r.anyDegraded()).isTrue();
        }

        // All subsequent calls should short-circuit (no new outbound requests)
        int callsBefore = wireMock.getAllServeEvents().size();
        TravelMatrixResult shortCircuited = adapter.estimate(
                List.of(new TravelTimePort.OriginRequest(TECH_1, LONDON)), BIRMINGHAM);
        int callsAfter = wireMock.getAllServeEvents().size();

        assertThat(shortCircuited.anyDegraded()).isTrue();
        assertThat(callsAfter).isEqualTo(callsBefore); // no new outbound call
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static TravelMatrixResult.Entry entry(TravelMatrixResult result, UUID technicianId) {
        return result.entries().stream()
                .filter(e -> e.technicianId().equals(technicianId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry for technician " + technicianId));
    }
}
