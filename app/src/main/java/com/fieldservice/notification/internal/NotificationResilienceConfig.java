package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wires the notification delivery beans for the {@code worker} Spring profile.
 *
 * <p>Circuit breaker configuration:
 * <ul>
 *   <li>50% failure rate threshold, 20-call sliding window</li>
 *   <li>30s wait in OPEN state, 3 probe calls in HALF_OPEN</li>
 * </ul>
 *
 * <p>Retry configuration:
 * <ul>
 *   <li>2 attempts total (1 retry on transient errors)</li>
 *   <li>Jittered exponential backoff: base 200ms ± 50%</li>
 *   <li>Retries on {@link ProviderTransientException} and {@link java.io.IOException}</li>
 * </ul>
 */
@Configuration
@Profile("worker")
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationResilienceConfig.class);
    private static final String CB_NAME = "notification";

    @Bean
    @ConditionalOnProperty(name = "notification.provider", havingValue = "HTTP")
    ExternalNotificationAdapter httpExternalNotificationAdapter(NotificationProperties props,
                                                                 Environment environment) {
        NotificationProperties.Http http = props.getHttp();
        log.info("notification_http_adapter initializing baseUrl={}", sanitize(http.getBaseUrl()));

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(http.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(http.getReadTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(http.getBaseUrl())
                .requestFactory(factory)
                .build();

        NotificationProviderSecrets secrets = new EnvironmentNotificationSecrets(environment);
        return new HttpExternalNotificationAdapter(restClient, secrets, http.getBaseUrl());
    }

    @Bean
    @ConditionalOnProperty(name = "notification.provider", havingValue = "NONE", matchIfMissing = true)
    ExternalNotificationAdapter stubExternalNotificationAdapter() {
        log.info("notification_stub_adapter active — no external provider configured");
        return new StubExternalNotificationAdapter();
    }

    @Bean
    NotificationPort notificationPort(ExternalNotificationAdapter externalAdapter,
                                       InAppFallbackAdapter inAppAdapter,
                                       DeliveryAttemptRepository attemptRepository,
                                       NotificationMetrics metrics) {

        CircuitBreaker circuitBreaker = CircuitBreaker.of(CB_NAME,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(20)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build());

        circuitBreaker.getEventPublisher().onStateTransition(
                event -> log.warn("notification circuit-breaker transition={} name={}",
                        event.getStateTransition(), CB_NAME));

        Retry retry = Retry.of(CB_NAME,
                RetryConfig.custom()
                        .maxAttempts(2)
                        .intervalFunction(attempt -> {
                            long base = 200L;
                            long jitter = (long) (base * 0.5 * (Math.random() * 2 - 1));
                            return Math.max(50, base + jitter);
                        })
                        .retryExceptions(ProviderTransientException.class, java.io.IOException.class)
                        .ignoreExceptions(ProviderPermanentException.class)
                        .build());

        metrics.bindBreakerGauge(circuitBreaker);

        return new ResilientNotificationDispatcher(
                externalAdapter, inAppAdapter, attemptRepository, circuitBreaker, retry, metrics);
    }

    private static String sanitize(String url) {
        if (url == null) return "null";
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getScheme() + "://" + u.getHost();
        } catch (Exception e) {
            return "<invalid>";
        }
    }
}
