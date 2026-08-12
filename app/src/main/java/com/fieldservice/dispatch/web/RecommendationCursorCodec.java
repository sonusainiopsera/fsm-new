package com.fieldservice.dispatch.web;

import com.fieldservice.platform.pagination.InvalidCursorException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and decodes opaque keyset cursors for the recommendations endpoint.
 *
 * <p>Cursor format (before base64url outer-encoding):
 * <pre>{@code <base64url(score:technicianId)>.<base64url(hmac)>}</pre>
 *
 * <p>The HMAC-SHA256 signature prevents tampering. An unrecognisable or tampered
 * cursor throws {@link InvalidCursorException} → HTTP 400.
 */
@Component
public class RecommendationCursorCodec {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] signingKey;

    public RecommendationCursorCodec() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.signingKey = key;
    }

    /** For tests: supply a deterministic key. */
    RecommendationCursorCodec(byte[] signingKey) {
        this.signingKey = signingKey.clone();
    }

    public record CursorPosition(double score, UUID technicianId) {}

    /**
     * Encodes a cursor position into an opaque, tamper-evident string.
     *
     * @param score        composite score at the boundary
     * @param technicianId technician ID at the boundary
     * @return opaque base64url cursor safe for HTTP query parameters
     */
    public String encode(double score, UUID technicianId) {
        String payload = score + ":" + technicianId;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] mac = hmac(payloadBytes);
        String encodedPayload = ENCODER.encodeToString(payloadBytes);
        String encodedMac = ENCODER.encodeToString(mac);
        return encodedPayload + "." + encodedMac;
    }

    /**
     * Decodes an opaque cursor, verifying its HMAC before returning the position.
     *
     * @param cursor the cursor string from the client
     * @return decoded cursor position
     * @throws InvalidCursorException if the cursor is malformed or has been tampered with
     */
    public CursorPosition decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException("Cursor must not be blank");
        }

        int dot = cursor.lastIndexOf('.');
        if (dot < 1 || dot >= cursor.length() - 1) {
            throw new InvalidCursorException("Cursor format invalid");
        }

        byte[] payloadBytes;
        byte[] providedMac;
        try {
            payloadBytes = DECODER.decode(cursor.substring(0, dot));
            providedMac = DECODER.decode(cursor.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor is not valid base64url", e);
        }

        byte[] expectedMac = hmac(payloadBytes);
        if (!constantTimeEquals(expectedMac, providedMac)) {
            throw new InvalidCursorException("Cursor signature mismatch — possible tampering");
        }

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        int colon = payload.indexOf(':');
        if (colon < 0) {
            throw new InvalidCursorException("Cursor payload malformed");
        }
        try {
            double score = Double.parseDouble(payload.substring(0, colon));
            UUID technicianId = UUID.fromString(payload.substring(colon + 1));
            return new CursorPosition(score, technicianId);
        } catch (NumberFormatException | IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor payload values invalid", e);
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
