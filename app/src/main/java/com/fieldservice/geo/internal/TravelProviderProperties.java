package com.fieldservice.geo.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Travel-time provider configuration.
 *
 * <p>Bound from the {@code geo.travel} prefix. Missing or misconfigured values
 * are detected at context startup by {@link TravelProviderAllowList}.
 */
@ConfigurationProperties(prefix = "geo.travel")
public record TravelProviderProperties(
        Provider provider,
        Resilience resilience,
        Cache cache) {

    public record Provider(
            @DefaultValue("https://maps.example.com") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("maps.example.com") List<String> allowedHosts,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("1500ms") Duration readTimeout,
            @DefaultValue("car") String travelMode,
            @DefaultValue("50") double averageSpeedKph) {}

    public record Resilience(
            @DefaultValue("1500ms") Duration timeLimiterTimeout,
            @DefaultValue("2") int maxAttempts,
            @DefaultValue("50") float circuitBreakerFailureRateThreshold,
            @DefaultValue("20") int circuitBreakerWindowSize,
            @DefaultValue("30s") Duration circuitBreakerWaitDuration,
            @DefaultValue("3") int circuitBreakerHalfOpenCalls) {}

    public record Cache(
            @DefaultValue("300") long ttlSeconds,
            @DefaultValue("4") int coordinatePrecisionDecimalPlaces) {}
}
