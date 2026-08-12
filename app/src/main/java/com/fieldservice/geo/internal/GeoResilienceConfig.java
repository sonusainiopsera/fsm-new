package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelTimePort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Wires the geo travel-time adapter for non-test profiles.
 *
 * <p>Excluded from the {@code test} profile. Tests construct the adapter
 * directly with WireMock as the HTTP backend.
 *
 * <p>Resilience parameters:
 * <ul>
 *   <li>TimeLimiter: 1.5 s total budget (includes retries).</li>
 *   <li>Retry: 2 max attempts, jittered exponential backoff (base 100 ms ± 50%).</li>
 *   <li>CircuitBreaker: opens at 50% failure over 20-call sliding window,
 *       waits 30 s before entering half-open state.</li>
 * </ul>
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(GeoProperties.class)
public class GeoResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(GeoResilienceConfig.class);
    static final String INSTANCE_NAME = "geo-travel";

    /** Shared CircuitBreaker instance — used by adapter and health indicator. */
    @Bean
    public CircuitBreaker travelCircuitBreaker(GeoProperties props) {
        GeoProperties.Resilience res = props.resilience();
        float failureRate = res.circuitBreakerFailureRateThreshold() > 0
                ? res.circuitBreakerFailureRateThreshold() : 50f;
        int windowSize = res.circuitBreakerSlidingWindowSize() > 0
                ? res.circuitBreakerSlidingWindowSize() : 20;
        Duration waitDuration = res.circuitBreakerWaitDuration() != null
                ? res.circuitBreakerWaitDuration() : Duration.ofSeconds(30);

        CircuitBreaker cb = CircuitBreaker.of(INSTANCE_NAME,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(failureRate)
                        .slidingWindowSize(windowSize)
                        .waitDurationInOpenState(waitDuration)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build());

        cb.getEventPublisher().onStateTransition(
                event -> log.warn("geo.travel circuit-breaker transition={} name={}",
                        event.getStateTransition(), INSTANCE_NAME));
        return cb;
    }

    @Bean
    public TravelCacheGateway travelCacheGateway(
            RedisConnectionFactory connectionFactory,
            GeoProperties props) {

        RedisTemplate<String, Double> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericToStringSerializer<>(Double.class));
        template.afterPropertiesSet();

        Duration ttl = props.cache() != null && props.cache().ttl() != null
                ? props.cache().ttl() : Duration.ofSeconds(300);

        return new RedisTravelCacheGateway(template, ttl);
    }

    @Bean
    public TravelTimePort travelTimePort(
            GeoProperties props,
            GeoMetrics geoMetrics,
            TravelCacheGateway travelCacheGateway,
            CircuitBreaker travelCircuitBreaker) {

        GeoProperties.Provider prov = props.provider();
        GeoProperties.Resilience res = props.resilience();

        log.info("geo.travel initializing baseUrl={} allowedHosts={}",
                sanitize(prov.baseUrl()), prov.allowedHosts());

        // SSRF protection — fails fast at startup if host not allow-listed
        TravelTimeEgressAllowList egressAllowList = new TravelTimeEgressAllowList(prov.allowedHosts());
        egressAllowList.validate(prov.baseUrl());

        // HTTP client
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(prov.connectTimeout() != null ? prov.connectTimeout() : Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(prov.readTimeout() != null ? prov.readTimeout() : Duration.ofSeconds(2));

        RestClient restClient = RestClient.builder()
                .baseUrl(prov.baseUrl())
                .requestFactory(factory)
                .build();

        Duration timeLimiterTimeout = res.timeLimiterTimeout() != null
                ? res.timeLimiterTimeout() : Duration.ofMillis(1500);

        TimeLimiter timeLimiter = TimeLimiter.of(INSTANCE_NAME,
                TimeLimiterConfig.custom()
                        .timeoutDuration(timeLimiterTimeout)
                        .cancelRunningFuture(true)
                        .build());

        int maxAttempts = res.maxRetryAttempts() > 0 ? res.maxRetryAttempts() : 2;
        Retry retry = Retry.of(INSTANCE_NAME,
                RetryConfig.custom()
                        .maxAttempts(maxAttempts)
                        .intervalFunction(attempt -> {
                            long base = 100L * (1L << (attempt - 1));
                            long jitter = (long) (base * 0.5 * (Math.random() * 2 - 1));
                            return Math.max(50L, base + jitter);
                        })
                        .retryExceptions(java.io.IOException.class,
                                java.net.ConnectException.class,
                                org.springframework.web.client.ResourceAccessException.class)
                        .build());

        HaversineEstimator haversine = new HaversineEstimator(
                prov.avgSpeedKmh() > 0 ? prov.avgSpeedKmh() : 30.0);

        // API key — injected from ${TRAVEL_PROVIDER_API_KEY}; never logged
        String apiKey = prov.apiKey() != null ? prov.apiKey() : "";

        var executor = Executors.newVirtualThreadPerTaskExecutor();

        return new TravelTimeProviderAdapter(
                restClient, prov.baseUrl(), apiKey,
                travelCacheGateway, haversine, egressAllowList,
                travelCircuitBreaker, timeLimiter, retry, executor, geoMetrics);
    }

    private static String sanitize(String url) {
        if (url == null) return "null";
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "<invalid>";
        }
    }
}
