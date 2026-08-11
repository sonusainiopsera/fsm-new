package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiGatewayPort;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Wires the AI gateway bean for non-test profiles.
 *
 * <p>The resulting {@link AiGatewayPort} bean is:
 * {@code EgressAllowList → HttpAiProviderAdapter (Resilience4j) → FeatureFlagGuardAdapter}
 *
 * <p>Excluded from the {@code test} profile; the test profile registers a
 * {@link com.fieldservice.aigateway.api.AiGatewayPort} via {@code FakeAiGatewayConfiguration}.
 */
@Configuration
@Profile("!test")
@EnableConfigurationProperties(AiGatewayProperties.class)
class AiGatewayResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayResilienceConfig.class);
    private static final String INSTANCE_NAME = "ai-gateway";

    @Bean
    AiGatewayPort aiGatewayPort(AiGatewayProperties props,
                                 AiGatewayMetrics gatewayMetrics,
                                 Environment environment,
                                 Optional<RedisUsageCapService> capService) {

        AiGatewayProperties.Provider prov = props.provider();
        log.info("ai_gateway initializing baseUrl={} allowedHosts={} enabled={}",
                sanitize(prov.baseUrl()), prov.allowedHosts(), props.copilot().enabled());

        // SSRF protection
        EgressAllowList egressAllowList = new EgressAllowList(prov.allowedHosts());

        // Secrets
        SecretsProvider secrets = new EnvironmentSecretsProvider(environment);

        // HTTP client with explicit timeouts and no redirect following
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(prov.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(prov.readTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(prov.baseUrl())
                .requestFactory(factory)
                .build();

        // Resilience4j components
        CircuitBreaker circuitBreaker = CircuitBreaker.of(INSTANCE_NAME,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(20)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build());

        circuitBreaker.getEventPublisher().onStateTransition(
                event -> log.warn("ai_gateway circuit-breaker transition={} name={}",
                        event.getStateTransition(), INSTANCE_NAME));

        Bulkhead bulkhead = Bulkhead.of(INSTANCE_NAME,
                BulkheadConfig.custom()
                        .maxConcurrentCalls(16)
                        .maxWaitDuration(Duration.ZERO)
                        .build());

        TimeLimiter timeLimiter = TimeLimiter.of(INSTANCE_NAME,
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(10))
                        .cancelRunningFuture(true)
                        .build());

        Retry retry = Retry.of(INSTANCE_NAME,
                RetryConfig.custom()
                        .maxAttempts(2)
                        // Jittered backoff: base 200ms ± 50%
                        .intervalFunction(attempt -> {
                            long base = 200L;
                            long jitter = (long) (base * 0.5 * (Math.random() * 2 - 1));
                            return Math.max(50, base + jitter);
                        })
                        .retryExceptions(java.io.IOException.class,
                                java.net.ConnectException.class)
                        .build());

        // Dedicated virtual-thread executor — AI calls never run on the Tomcat pool
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        HttpAiProviderAdapter httpAdapter = new HttpAiProviderAdapter(
                restClient, egressAllowList, secrets,
                circuitBreaker, bulkhead, timeLimiter, retry,
                executor, gatewayMetrics, prov.baseUrl(), prov.costPerToken());

        // WO-192: wrap with PII redaction before the feature-flag guard so prompts
        // are scrubbed before any logging or outbound call occurs.
        RedactingAiProviderAdapter redactingAdapter =
                new RedactingAiProviderAdapter(httpAdapter, new FreeTextScrubber());

        return new FeatureFlagGuardAdapter(
                props.copilot().enabled(),
                redactingAdapter,
                capService.orElse(null));
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
