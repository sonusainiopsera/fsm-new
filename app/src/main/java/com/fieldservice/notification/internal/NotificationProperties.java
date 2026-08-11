package com.fieldservice.notification.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Notification module configuration properties bound from {@code notification.*}.
 *
 * <p>Credentials are resolved separately from the secrets provider; do not put
 * credential values in application.properties or any committed configuration file.
 */
@ConfigurationProperties(prefix = "notification")
class NotificationProperties {

    /** Adapter selection: NONE (stub, default) or HTTP. */
    private String provider = "NONE";

    private final Http http = new Http();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public Http getHttp() { return http; }

    static class Http {
        /** Base URL for the HTTP notification provider. */
        private String baseUrl = "https://notifications.example.invalid";

        /** Connect timeout for outbound provider calls. */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** Read timeout for outbound provider calls. */
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }
}
