package com.fieldservice.aigateway.internal;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Creates and wires the Resilience4j components for the AI gateway. */
class AiGatewayResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayResilienceConfig.class);
    private static final Random JITTER = new Random();

    static CircuitBreaker circuitBreaker(AiGatewayProperties props) {
        var cfg = props.resilience().circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.failureRateThreshold())
                .slidingWindowSize(cfg.slidingWindowSize())
                .waitDurationInOpenState(cfg.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cfg.permittedCallsInHalfOpenState())
                .build();

        CircuitBreaker cb = CircuitBreaker.of("ai-provider", config);
        cb.getEventPublisher()
                .onStateTransition(e ->
                        log.warn("ai_circuit_breaker_transition name={} from={} to={}",
                                e.getCircuitBreakerName(),
                                e.getStateTransition().getFromState(),
                                e.getStateTransition().getToState()));
        return cb;
    }

    static Bulkhead bulkhead(AiGatewayProperties props) {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(props.resilience().bulkhead().maxConcurrentCalls())
                .build();
        return Bulkhead.of("ai-provider", config);
    }

    static TimeLimiter timeLimiter(AiGatewayProperties props) {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(props.resilience().timeLimiter().timeoutDuration())
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of("ai-provider", config);
    }

    static Retry retry(AiGatewayProperties props) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2) // 1 retry on top of initial attempt
                .retryOnException(t -> t instanceof ResourceAccessException) // connection-level only
                .intervalFunction(attempt -> {
                    // jittered backoff: 100ms + up to 200ms jitter
                    return 100L + (long)(JITTER.nextDouble() * 200);
                })
                .build();
        return Retry.of("ai-provider", config);
    }

    static ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
