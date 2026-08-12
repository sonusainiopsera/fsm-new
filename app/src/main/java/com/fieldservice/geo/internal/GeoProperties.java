package com.fieldservice.geo.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration properties for the geo travel-time module.
 * Bound from the {@code geo.travel} prefix in application.yml.
 */
@ConfigurationProperties("geo.travel")
record GeoProperties(
        Provider provider,
        Cache cache,
        Resilience resilience) {

    record Provider(
            /** Base URL for the travel-time provider — no user-supplied path. */
            String baseUrl,
            /**
             * API key for the travel provider — resolved from ${TRAVEL_PROVIDER_API_KEY}.
             * Never logged.
             */
            String apiKey,
            /** Allow-listed FQDNs for SSRF protection. */
            List<String> allowedHosts,
            Duration connectTimeout,
            Duration readTimeout,
            /** Average speed in km/h used by the Haversine fallback. */
            double avgSpeedKmh) {}

    record Cache(
            /** TTL for cached travel-time entries. */
            Duration ttl) {}

    record Resilience(
            Duration timeLimiterTimeout,
            int maxRetryAttempts,
            float circuitBreakerFailureRateThreshold,
            int circuitBreakerSlidingWindowSize,
            Duration circuitBreakerWaitDuration) {}
}
