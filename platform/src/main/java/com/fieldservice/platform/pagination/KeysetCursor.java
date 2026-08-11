package com.fieldservice.platform.pagination;

import com.fieldservice.platform.api.exception.InvalidCursorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and decodes tamper-evident keyset pagination cursors.
 *
 * <h3>Wire format</h3>
 * <pre>
 * &lt;base64url(payload)&gt;.&lt;base64url(HMAC-SHA256)&gt;
 *
 * payload (compact, no whitespace):
 *   {"ct":&lt;epochMilli&gt;,"id":"&lt;uuid&gt;","fp":"&lt;sort_fingerprint&gt;"}
 * </pre>
 *
 * <p>The HMAC signature makes any bit-level tampering detectable. The sort-order
 * fingerprint ({@code fp}) prevents replaying a cursor issued against one sort order
 * against a different ordering — the server rejects the cursor with 400 rather than
 * silently misapplying it.
 *
 * <p>Decoding failures (malformed base64, JSON parse errors, HMAC mismatch, fingerprint
 * mismatch) all map to {@link InvalidCursorException} which the
 * {@link com.fieldservice.platform.web.GlobalExceptionHandler} converts to HTTP 400.
 */
public final class KeysetCursor {

    private static final Logger log = LoggerFactory.getLogger(KeysetCursor.class);
    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] hmacKeyBytes;

    public KeysetCursor(String hmacKey) {
        this.hmacKeyBytes = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes a cursor from the last row's {@code createdAt} and {@code id} values
     * and the current sort-order fingerprint.
     *
     * @param lastCreatedAt epoch-millis of the last row's {@code created_at}
     * @param lastId        id of the last row
     * @param sortFingerprint canonical sort description, e.g. {@code "created_at:desc,id:asc"}
     * @return an opaque, tamper-evident cursor string safe for URL inclusion
     */
    public String encode(Instant lastCreatedAt, UUID lastId, String sortFingerprint) {
        String payload = "{\"ct\":" + lastCreatedAt.toEpochMilli()
                + ",\"id\":\"" + lastId + "\""
                + ",\"fp\":\"" + sortFingerprint + "\"}";
        String payloadB64 = b64(payload.getBytes(StandardCharsets.UTF_8));
        String hmacB64    = b64(hmac(payload));
        return payloadB64 + "." + hmacB64;
    }

    /**
     * Decodes and validates a cursor, returning the encapsulated values if valid.
     *
     * @param cursor          the opaque cursor string from the client
     * @param sortFingerprint the current sort-order fingerprint; must match what was encoded
     * @return a decoded {@link Payload}
     * @throws InvalidCursorException if the cursor is malformed, tampered, or for a
     *                                different sort order
     */
    public Payload decode(String cursor, String sortFingerprint) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException("Cursor must not be blank.");
        }
        String[] parts = cursor.split("\\.");
        if (parts.length != 2) {
            throw new InvalidCursorException("Cursor format invalid.");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor payload is not valid base64url.", e);
        }
        // Verify HMAC before parsing payload to prevent oracle attacks
        String payloadStr = new String(payloadBytes, StandardCharsets.UTF_8);
        byte[] expectedHmac = hmac(payloadStr);
        byte[] receivedHmac;
        try {
            receivedHmac = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor signature is not valid base64url.", e);
        }
        if (!constantTimeEquals(expectedHmac, receivedHmac)) {
            log.warn("keyset_cursor_tamper_detected");
            throw new InvalidCursorException("Cursor signature verification failed.");
        }

        // Parse payload — minimal JSON parse (no external dependency)
        Payload p = parsePayload(payloadStr);
        if (!sortFingerprint.equals(p.sortFingerprint())) {
            log.warn("keyset_cursor_sort_mismatch expected='{}' got='{}'",
                    sortFingerprint, p.sortFingerprint());
            throw new InvalidCursorException(
                    "Cursor was issued for a different sort order and cannot be reused.");
        }
        return p;
    }

    // ---- payload parsing (lightweight, no Jackson dependency required here) -----

    private static Payload parsePayload(String json) {
        try {
            long ct       = extractLong(json, "\"ct\":");
            String id     = extractString(json, "\"id\":");
            String fp     = extractString(json, "\"fp\":");
            return new Payload(Instant.ofEpochMilli(ct), UUID.fromString(id), fp);
        } catch (Exception e) {
            throw new InvalidCursorException("Cursor payload structure is invalid.", e);
        }
    }

    private static long extractLong(String json, String key) {
        int start = json.indexOf(key) + key.length();
        int end   = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end).strip());
    }

    private static String extractString(String json, String key) {
        int keyPos  = json.indexOf(key);
        int start   = json.indexOf('"', keyPos + key.length()) + 1;
        int end     = json.indexOf('"', start);
        return json.substring(start, end);
    }

    // ---- HMAC helpers -----------------------------------------------------------

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(hmacKeyBytes, HMAC_ALG));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /**
     * Decoded cursor payload.
     */
    public record Payload(Instant lastCreatedAt, UUID lastId, String sortFingerprint) {}
}
