package com.fieldservice.dispatch.web;

import com.fieldservice.platform.api.exception.InvalidCursorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and decodes tamper-evident recommendation keyset pagination cursors.
 *
 * <h3>Wire format</h3>
 * <pre>
 * &lt;base64url(payload)&gt;.&lt;base64url(HMAC-SHA256)&gt;
 *
 * payload (compact JSON):
 *   {"sc":"&lt;hex_double&gt;","id":"&lt;uuid&gt;","fp":"score:desc,id:asc"}
 * </pre>
 *
 * <p>The score is encoded as the hex representation of its IEEE-754 double bit pattern
 * so it round-trips exactly. The HMAC prevents client-side tampering. The sort-order
 * fingerprint prevents reuse across different orderings.
 *
 * <p>Any decode failure (malformed base64, JSON parse error, HMAC mismatch, wrong
 * fingerprint) throws {@link InvalidCursorException} which maps to HTTP 400.
 */
public final class RecommendationCursor {

    private static final Logger log        = LoggerFactory.getLogger(RecommendationCursor.class);
    private static final String HMAC_ALG   = "HmacSHA256";

    /** Fixed sort fingerprint: recommendations are always score DESC, then id ASC. */
    public static final String SORT_FP = "score:desc,id:asc";

    private final byte[] hmacKeyBytes;

    public RecommendationCursor(String hmacKey) {
        this.hmacKeyBytes = hmacKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes the last row's score and technician id as an opaque, tamper-evident cursor.
     *
     * @param score         composite score of the last returned candidate
     * @param technicianId  technician id of the last returned candidate
     * @return opaque cursor safe for URL inclusion
     */
    public String encode(double score, UUID technicianId) {
        String scoreBits = Long.toHexString(Double.doubleToRawLongBits(score));
        String payload = "{\"sc\":\"" + scoreBits + "\",\"id\":\"" + technicianId + "\",\"fp\":\"" + SORT_FP + "\"}";
        String payloadB64 = b64(payload.getBytes(StandardCharsets.UTF_8));
        String hmacB64    = b64(hmac(payload));
        return payloadB64 + "." + hmacB64;
    }

    /**
     * Decodes and validates the cursor, returning the encoded score and technician id.
     *
     * @param cursor opaque cursor string from the client
     * @return decoded {@link Payload}
     * @throws InvalidCursorException on malformed, tampered, or wrong-sort cursor
     */
    public Payload decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException("Recommendation cursor must not be blank.");
        }
        String[] parts = cursor.split("\\.");
        if (parts.length != 2) {
            throw new InvalidCursorException("Recommendation cursor format invalid.");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Recommendation cursor payload is not valid base64url.", e);
        }
        String payloadStr = new String(payloadBytes, StandardCharsets.UTF_8);
        byte[] expectedHmac = hmac(payloadStr);
        byte[] receivedHmac;
        try {
            receivedHmac = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Recommendation cursor signature is not valid base64url.", e);
        }
        if (!constantTimeEquals(expectedHmac, receivedHmac)) {
            log.warn("recommendation_cursor_tamper_detected");
            throw new InvalidCursorException("Recommendation cursor signature verification failed.");
        }
        return parsePayload(payloadStr);
    }

    private static Payload parsePayload(String json) {
        try {
            String scHex  = extractString(json, "\"sc\":");
            String idStr  = extractString(json, "\"id\":");
            String fp     = extractString(json, "\"fp\":");
            if (!SORT_FP.equals(fp)) {
                log.warn("recommendation_cursor_sort_mismatch expected='{}' got='{}'", SORT_FP, fp);
                throw new InvalidCursorException(
                        "Recommendation cursor was issued for a different sort order.");
            }
            double score = Double.longBitsToDouble(Long.parseUnsignedLong(scHex, 16));
            UUID   id    = UUID.fromString(idStr);
            return new Payload(score, id);
        } catch (InvalidCursorException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCursorException("Recommendation cursor payload structure is invalid.", e);
        }
    }

    private static String extractString(String json, String key) {
        int keyPos = json.indexOf(key);
        int start  = json.indexOf('"', keyPos + key.length()) + 1;
        int end    = json.indexOf('"', start);
        return json.substring(start, end);
    }

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
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    /** Decoded cursor payload. */
    public record Payload(double score, UUID technicianId) {}
}
