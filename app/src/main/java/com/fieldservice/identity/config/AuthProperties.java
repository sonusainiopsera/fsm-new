package com.fieldservice.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        Jwt jwt,
        RefreshToken refreshToken,
        Lockout lockout
) {
    public record Jwt(
            @DefaultValue("http://localhost:8080") String issuer,
            @DefaultValue("field-service-api") String audience,
            @DefaultValue("900") long accessTokenTtlSeconds,
            String rsaPrivateKeyPem
    ) {}

    public record RefreshToken(
            @DefaultValue("7") int ttlDays
    ) {
        public Duration ttl() {
            return Duration.ofDays(ttlDays);
        }
    }

    public record Lockout(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("900") long windowSeconds
    ) {}
}
