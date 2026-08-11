package com.fieldservice.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the transactional outbox.
 *
 * @param maxPayloadBytes  maximum serialised payload size in bytes; default 65536 (64 KB)
 * @param batchSize        rows claimed per poll pass; default 100
 * @param maxAttempts      max delivery attempts before dead-lettering; default 5
 * @param backoffBaseMs    base backoff in milliseconds; default 1000
 * @param backoffCapMs     cap on backoff in milliseconds; default 300000 (5 min)
 * @param jitterFraction   random jitter fraction (0.0–1.0); default 0.2 (20%)
 * @param dispatchTimeoutMs per-event dispatch timeout in milliseconds; default 10000
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        int    maxPayloadBytes,
        int    batchSize,
        int    maxAttempts,
        long   backoffBaseMs,
        long   backoffCapMs,
        double jitterFraction,
        long   dispatchTimeoutMs
) {

    public OutboxProperties {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("app.outbox.max-payload-bytes must be positive");
        }
    }

    public OutboxProperties() {
        this(65536, 100, 5, 1_000L, 300_000L, 0.2, 10_000L);
    }
}
