package com.fieldservice.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the transactional outbox.
 *
 * @param maxPayloadBytes maximum serialised payload size in bytes; default 65536 (64 KB)
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(int maxPayloadBytes) {

    public OutboxProperties {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("app.outbox.max-payload-bytes must be positive");
        }
    }

    public OutboxProperties() {
        this(65536);
    }
}
