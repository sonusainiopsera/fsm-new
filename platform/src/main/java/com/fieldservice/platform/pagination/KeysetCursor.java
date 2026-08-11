package com.fieldservice.platform.pagination;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque, tamper-evident keyset cursor for work-order pagination.
 *
 * <h3>Wire format</h3>
 * The cursor token is {@code base64url(payload) + "." + base64url(HMAC-SHA256(payload))}.
 * The payload is the UTF-8 string {@code "<epochMillis>:<uuid>:<direction>"}, where
 * {@code direction} is the sort-order fingerprint ({@code ASC} or {@code DESC}).
 *
 * <h3>Tamper detection</h3>
 * Any modification to the payload invalidates the HMAC signature.
 * Replaying a cursor against a different sort order is rejected because the
 * decoded direction fingerprint will differ from the request's direction.
 */
public record KeysetCursor(Instant createdAt, UUID id, String direction) {

    /**
     * Encodes this cursor as an opaque base64url token signed with {@code hmacSecret}.
     */
    public String encode(byte[] hmacSecret) {
        String payload = createdAt.toEpochMilli() + ":" + id + ":" + direction;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] sig = hmac(payloadBytes, hmacSecret);

        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(payloadBytes) + "." + enc.encodeToString(sig);
    }

    /**
     * Decodes and verifies a cursor token.
     *
     * @param token             the opaque cursor string from the client
     * @param hmacSecret        the server-side HMAC secret
     * @param expectedDirection the sort direction in the current request (fingerprint check)
     * @throws InvalidCursorException if the token is malformed, tampered, or has the wrong direction
     */
    public static KeysetCursor decode(String token, byte[] hmacSecret, String expectedDirection) {
        if (token == null || token.isBlank()) {
            throw new InvalidCursorException("cursor must not be blank");
        }

        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw new InvalidCursorException("malformed cursor — expected exactly one dot separator");
        }

        byte[] payloadBytes;
        byte[] sigBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            sigBytes     = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("cursor contains invalid base64url encoding");
        }

        byte[] expectedSig = hmac(payloadBytes, hmacSecret);
        if (!MessageDigest.isEqual(expectedSig, sigBytes)) {
            throw new InvalidCursorException("cursor signature verification failed");
        }

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split(":", 3);
        if (fields.length != 3) {
            throw new InvalidCursorException("cursor payload has unexpected structure");
        }

        Instant createdAt;
        UUID id;
        try {
            createdAt = Instant.ofEpochMilli(Long.parseLong(fields[0]));
            id = UUID.fromString(fields[1]);
        } catch (NumberFormatException | IllegalArgumentException e) {
            throw new InvalidCursorException("cursor payload contains invalid field values");
        }

        String direction = fields[2];
        if (!direction.equals(expectedDirection)) {
            throw new InvalidCursorException(
                "cursor sort-order fingerprint mismatch: cursor was issued for " + direction
                + " but request uses " + expectedDirection);
        }

        return new KeysetCursor(createdAt, id, direction);
    }

    private static byte[] hmac(byte[] data, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}
