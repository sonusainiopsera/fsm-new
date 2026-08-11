package com.fieldservice.aigateway.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "ai")
public record AiGatewayProperties(
        Copilot copilot,
        Provider provider,
        Resilience resilience,
        Metrics metrics) {

    public record Copilot(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("50") int dailyCapPerUser) {}

    public record Provider(
            @DefaultValue("https://api.example.ai") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("api.example.ai") List<String> allowedHosts,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {}

    public record Resilience(
            CircuitBreakerConfig circuitBreaker,
            BulkheadConfig bulkhead,
            TimeLimiterConfig timeLimiter) {

        public record CircuitBreakerConfig(
                @DefaultValue("50") float failureRateThreshold,
                @DefaultValue("20") int slidingWindowSize,
                @DefaultValue("30s") Duration waitDurationInOpenState,
                @DefaultValue("3") int permittedCallsInHalfOpenState) {}

        public record BulkheadConfig(
                @DefaultValue("16") int maxConcurrentCalls) {}

        public record TimeLimiterConfig(
                @DefaultValue("10s") Duration timeoutDuration) {}
    }

    public record Metrics(
            @DefaultValue("0.002") double costPer1kTokens) {}
}
