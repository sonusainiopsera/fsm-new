package com.fieldservice.notification.internal.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code notification.provider-http.*} configuration.
 * Credentials are sourced from the managed secrets store via environment variable
 * {@code NOTIFICATION_PROVIDER_API_KEY}; the value is never committed or logged.
 */
@ConfigurationProperties(prefix = "notification.provider-http")
public record NotificationProviderProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
    public NotificationProviderProperties {
        if (baseUrl == null)        baseUrl        = "http://localhost:8099";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(5);
        if (readTimeout == null)    readTimeout    = Duration.ofSeconds(10);
    }
}
