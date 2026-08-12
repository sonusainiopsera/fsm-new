package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelTimePort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring wiring for the geo module. Package-private — callers depend only on
 * {@link TravelTimePort}.
 *
 * <p>The allow-list validation runs inside {@link TravelProviderAllowList}'s constructor,
 * which is called during {@link #travelProviderAllowList} bean creation. A misconfigured
 * host fails context startup with a clear {@link IllegalStateException}.
 */
@Configuration
@EnableConfigurationProperties(TravelProviderProperties.class)
public class GeoAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GeoAutoConfiguration.class);
    private static final Random JITTER = new Random();

    // ---- Allow-list (startup SSRF gate) ----------------------------------------

    @Bean
    TravelProviderAllowList travelProviderAllowList(TravelProviderProperties props) {
        return new TravelProviderAllowList(props); // validates at construction
    }

    // ---- Resilience4j ----------------------------------------------------------

    @Bean("travelCircuitBreaker")
    CircuitBreaker travelCircuitBreaker(TravelProviderProperties props) {
        var cfg = props.resilience();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.circuitBreakerFailureRateThreshold())
                .slidingWindowSize(cfg.circuitBreakerWindowSize())
                .waitDurationInOpenState(cfg.circuitBreakerWaitDuration())
                .permittedNumberOfCallsInHalfOpenState(cfg.circuitBreakerHalfOpenCalls())
                .build();
        CircuitBreaker cb = CircuitBreaker.of("travel-provider", config);
        cb.getEventPublisher().onStateTransition(e ->
                log.warn("geo.travel.circuit_breaker_transition name={} from={} to={}",
                        e.getCircuitBreakerName(),
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
        return cb;
    }

    @Bean("travelTimeLimiter")
    TimeLimiter travelTimeLimiter(TravelProviderProperties props) {
        return TimeLimiter.of("travel-provider",
                TimeLimiterConfig.custom()
                        .timeoutDuration(props.resilience().timeLimiterTimeout())
                        .cancelRunningFuture(true)
                        .build());
    }

    @Bean("travelRetry")
    Retry travelRetry(TravelProviderProperties props) {
        return Retry.of("travel-provider",
                RetryConfig.custom()
                        .maxAttempts(props.resilience().maxAttempts())
                        .retryOnException(t -> t instanceof org.springframework.web.client.ResourceAccessException)
                        .intervalFunction(attempt -> {
                            // jittered exponential: 100ms base * 2^(attempt-1) + up to 100ms jitter
                            long base = (long)(100 * Math.pow(2, attempt - 1));
                            return Math.min(base + (long)(JITTER.nextDouble() * 100), 1_000L);
                        })
                        .build());
    }

    @Bean(name = "travelExecutor", destroyMethod = "shutdown")
    ExecutorService travelExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // ---- HTTP client -----------------------------------------------------------

    @Bean("travelRestClient")
    RestClient travelRestClient(TravelProviderProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.provider().connectTimeout().toMillis());
        factory.setReadTimeout((int) props.provider().readTimeout().toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ---- Domain beans ----------------------------------------------------------

    @Bean
    HaversineEstimator haversineEstimator(TravelProviderProperties props) {
        return new HaversineEstimator(props.provider().averageSpeedKph());
    }

    @Bean
    TravelCacheGateway travelCacheGateway(
            StringRedisTemplate stringRedisTemplate,
            TravelProviderProperties props) {
        return new TravelCacheGateway(stringRedisTemplate, props);
    }

    @Bean
    TravelMetrics travelMetrics(MeterRegistry meterRegistry) {
        return new TravelMetrics(meterRegistry);
    }

    @Bean
    TravelHealthIndicator travelHealthIndicator(
            @Qualifier("travelCircuitBreaker") CircuitBreaker travelCircuitBreaker) {
        return new TravelHealthIndicator(travelCircuitBreaker);
    }

    // ---- Primary port ----------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(TravelTimePort.class)
    TravelTimePort travelTimePort(
            @Qualifier("travelRestClient") RestClient travelRestClient,
            TravelProviderProperties props,
            TravelProviderAllowList travelProviderAllowList,
            TravelCacheGateway travelCacheGateway,
            HaversineEstimator haversineEstimator,
            TravelMetrics travelMetrics,
            @Qualifier("travelCircuitBreaker") CircuitBreaker travelCircuitBreaker,
            @Qualifier("travelTimeLimiter") TimeLimiter travelTimeLimiter,
            @Qualifier("travelRetry") Retry travelRetry,
            @Qualifier("travelExecutor") ExecutorService travelExecutor) {
        return new TravelTimeProviderAdapter(
                travelRestClient, props, travelProviderAllowList,
                travelCacheGateway, haversineEstimator, travelMetrics,
                travelCircuitBreaker, travelTimeLimiter, travelRetry, travelExecutor);
    }
}
