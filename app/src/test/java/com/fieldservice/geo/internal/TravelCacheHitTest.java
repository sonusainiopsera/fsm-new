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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a warm Redis cache prevents outbound HTTP calls (AC-4).
 */
@WireMockTest
@DisplayName("Travel cache hit prevents HTTP call (AC-4)")
class TravelCacheHitTest {

    private TravelTimeProviderAdapter adapter;

    private static final TravelCoordinate ORIGIN = new TravelCoordinate(51.500, -0.100);
    private static final TravelCoordinate DEST = new TravelCoordinate(51.600, -0.200);

    /** In-memory stub cache that returns a pre-populated entry. */
    static class StubCacheGateway implements TravelCacheGateway {
        private final Map<String, Double> store = new HashMap<>();

        void seed(TravelCoordinate origin, TravelCoordinate destination, double minutes) {
            store.put(CoordinateRounder.cacheKey(origin, destination), minutes);
        }

        @Override
        public Optional<Double> get(TravelCoordinate origin, TravelCoordinate destination) {
            return Optional.ofNullable(store.get(CoordinateRounder.cacheKey(origin, destination)));
        }

        @Override
        public void put(TravelCoordinate origin, TravelCoordinate destination, double minutes) {
            store.put(CoordinateRounder.cacheKey(origin, destination), minutes);
        }
    }

    private final StubCacheGateway stubCache = new StubCacheGateway();

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        String baseUrl = wm.getHttpBaseUrl();

        TravelTimeEgressAllowList noOpAllowList = new TravelTimeEgressAllowList(
                List.of("localhost", "127.0.0.1")) {
            @Override
            void validate(String url) {}
        };

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        CircuitBreaker cb = CircuitBreaker.of("cache-test-cb",
                CircuitBreakerConfig.ofDefaults());

        TimeLimiter tl = TimeLimiter.of("cache-test-tl",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(2)).build());

        Retry retry = Retry.of("cache-test-retry",
                RetryConfig.custom().maxAttempts(1).build());

        GeoMetrics metrics = new GeoMetrics(new SimpleMeterRegistry());
        HaversineEstimator haversine = new HaversineEstimator(30.0);

        adapter = new TravelTimeProviderAdapter(
                restClient, baseUrl, "test-key",
                stubCache, haversine, noOpAllowList,
                cb, tl, retry,
                Executors.newVirtualThreadPerTaskExecutor(), metrics);
    }

    @Test
    @DisplayName("Second identical request served from cache — no outbound HTTP call (AC-4)")
    void secondRequest_servedFromCache_noHttpCall() {
        // Seed the cache so the adapter has a hit immediately
        stubCache.seed(ORIGIN, DEST, 18.0);

        // No WireMock stub registered — an HTTP call would cause a 404 or connection error
        TravelMatrixResult result = adapter.estimateTravelTime(List.of(ORIGIN), DEST);

        assertThat(result.anyDegraded()).isFalse();
        assertThat(result.entries().get(0).estimatedMinutes()).isEqualTo(18.0);

        // Verify no HTTP calls were made to WireMock
        verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("geo.travel.cache.hit counter increments on cache hit")
    void cacheHit_metricIncremented() {
        stubCache.seed(ORIGIN, DEST, 10.0);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GeoMetrics metrics = new GeoMetrics(registry);

        TravelTimeEgressAllowList noOpAllowList = new TravelTimeEgressAllowList(List.of()) {
            @Override void validate(String url) {}
        };

        TravelTimeProviderAdapter adapterWithMetrics = new TravelTimeProviderAdapter(
                null, "http://unused", "key", stubCache,
                new HaversineEstimator(30.0), noOpAllowList,
                CircuitBreaker.ofDefaults("m-cb"),
                TimeLimiter.ofDefaults("m-tl"),
                Retry.ofDefaults("m-retry"),
                Executors.newVirtualThreadPerTaskExecutor(),
                metrics);

        adapterWithMetrics.estimateTravelTime(List.of(ORIGIN), DEST);

        var counter = registry.find(GeoMetrics.CACHE_HIT)
                .tag("provider", TravelTimeProviderAdapter.PROVIDER)
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
