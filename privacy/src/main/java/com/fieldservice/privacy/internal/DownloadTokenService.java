package com.fieldservice.privacy.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Mints and validates short-lived HMAC-signed download tokens for DSAR export artifacts.
 *
 * <p>Token format (Base64URL-encoded): {@code artifactId|expiresAt|hmac}
 * where {@code hmac = HMAC-SHA256(secret, artifactId + "|" + expiresAt)}.
 *
 * <p>This is a Phase 1 stub implementation. Production deployments should replace
 * the secret with a key from Secrets Manager and rotate it periodically.
 */
@Component
class DownloadTokenService {

    private static final Logger log = LoggerFactory.getLogger(DownloadTokenService.class);
    private static final String ALGO = "HmacSHA256";

    private final Clock           clock;
    private final DsarProperties  properties;
    private final byte[]          secret;

    DownloadTokenService(Clock clock, DsarProperties properties,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${app.privacy.dsar.token-secret:change-me-in-production}") String tokenSecret) {
        this.clock      = clock;
        this.properties = properties;
        this.secret     = tokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Mints a short-lived token for {@code artifactId}.
     *
     * @return encoded token string
     */
    String mint(UUID artifactId) {
        Instant expiresAt = clock.instant().plusSeconds(properties.getDownloadTokenValiditySecs());
        String payload    = artifactId + "|" + expiresAt.getEpochSecond();
        String hmac       = hmac(payload);
        String raw        = payload + "|" + hmac;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates a token and returns the artifact id if valid and not expired.
     *
     * @throws InvalidDownloadTokenException if the token is invalid or expired
     */
    UUID validate(String token) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidDownloadTokenException("Malformed download token");
        }

        String[] parts = raw.split("\\|", 3);
        if (parts.length != 3) {
            throw new InvalidDownloadTokenException("Malformed download token structure");
        }

        UUID    artifactId = UUID.fromString(parts[0]);
        long    epochSecs  = Long.parseLong(parts[1]);
        String  givenHmac  = parts[2];

        Instant expiresAt  = Instant.ofEpochSecond(epochSecs);
        if (clock.instant().isAfter(expiresAt)) {
            throw new InvalidDownloadTokenException("Download token has expired");
        }

        String expectedHmac = hmac(parts[0] + "|" + parts[1]);
        if (!expectedHmac.equals(givenHmac)) {
            log.warn("download_token_invalid_hmac artifactId={}", artifactId);
            throw new InvalidDownloadTokenException("Invalid download token signature");
        }

        return artifactId;
    }

    Instant expiresAt(String token) {
        String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", 3);
        return Instant.ofEpochSecond(Long.parseLong(parts[1]));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret, ALGO));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC configuration error", e);
        }
    }

    static class InvalidDownloadTokenException extends RuntimeException {
        InvalidDownloadTokenException(String message) {
            super(message);
        }
    }
}
