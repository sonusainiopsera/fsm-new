package com.fieldservice.platform.pagination;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tamper-evident keyset cursor for paginated work-order traversal.
 *
 * <p>The cursor encodes the sort-key tuple of the last row on a page — for the default
 * work-order sort this is {@code (createdAt DESC, id ASC)} — along with a sort-order
 * fingerprint that detects cross-sort-order replay.
 *
 * <p>Wire format: {@code BASE64URL(payload).BASE64URL(HMAC-SHA256(payload-bytes))}<br>
 * where {@code payload = {"fp":"<fingerprint>","ca":<epochMillis>,"id":"<uuid>"}}
 *
 * <p>Decoding fails with {@link InvalidCursorException} on any of:
 * <ul>
 *   <li>Malformed base64url or JSON</li>
 *   <li>HMAC mismatch (tampered cursor)</li>
 *   <li>Fingerprint mismatch (cursor from a different sort order)</li>
 * </ul>
 */
public final class KeysetCursor {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    // Strict pattern — we only parse cursors we encoded; values are validated post-decode
    private static final Pattern PAYLOAD_PATTERN = Pattern.compile(
            "\\{\"fp\":\"([^\"]+)\",\"ca\":(\\d+),\"id\":\"([0-9a-f\\-]+)\"\\}");

    private final String sortFingerprint;
    private final Instant createdAt;
    private final UUID id;

    public KeysetCursor(String sortFingerprint, Instant createdAt, UUID id) {
        this.sortFingerprint = sortFingerprint;
        this.createdAt = createdAt;
        this.id = id;
    }

    public String sortFingerprint() { return sortFingerprint; }
    public Instant createdAt()      { return createdAt; }
    public UUID id()                { return id; }

    /**
     * Encodes this cursor to its opaque wire representation.
     *
     * @param secret HMAC signing key (UTF-8); must be at least 32 characters for security
     * @return opaque cursor string safe for use as a URL query parameter
     */
    public String encode(String secret) {
        String payload = String.format(
                "{\"fp\":\"%s\",\"ca\":%d,\"id\":\"%s\"}",
                sortFingerprint, createdAt.toEpochMilli(), id.toString());
        String payloadB64 = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        byte[] hmac = hmacSha256(secret, payloadB64.getBytes(StandardCharsets.UTF_8));
        return payloadB64 + "." + ENC.encodeToString(hmac);
    }

    /**
     * Decodes and verifies a cursor string.
     *
     * @param cursor              the opaque cursor from the client
     * @param expectedFingerprint the sort-order fingerprint for the current request
     * @param secret              the HMAC signing key
     * @return decoded cursor
     * @throws InvalidCursorException if the cursor is malformed, tampered, or from the wrong sort order
     */
    public static KeysetCursor decode(String cursor, String expectedFingerprint, String secret) {
        if (cursor == null || cursor.isBlank()) {
            throw new InvalidCursorException("Cursor must not be blank.");
        }
        int dot = cursor.lastIndexOf('.');
        if (dot < 1 || dot == cursor.length() - 1) {
            throw new InvalidCursorException("Cursor format is invalid.");
        }
        String payloadPart = cursor.substring(0, dot);
        String sigPart     = cursor.substring(dot + 1);

        // Verify HMAC before decoding payload (timing-safe comparison)
        byte[] expectedHmac = hmacSha256(secret, payloadPart.getBytes(StandardCharsets.UTF_8));
        byte[] actualHmac;
        try {
            actualHmac = DEC.decode(sigPart);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor signature is not valid base64url.", e);
        }
        if (!MessageDigest.isEqual(expectedHmac, actualHmac)) {
            throw new InvalidCursorException("Cursor signature does not match; cursor may have been tampered with.");
        }

        // Decode payload
        byte[] payloadBytes;
        try {
            payloadBytes = DEC.decode(payloadPart);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor payload is not valid base64url.", e);
        }
        String json = new String(payloadBytes, StandardCharsets.UTF_8);

        Matcher m = PAYLOAD_PATTERN.matcher(json);
        if (!m.matches()) {
            throw new InvalidCursorException("Cursor payload JSON is unrecognisable.");
        }
        String fp  = m.group(1);
        long   ca  = Long.parseLong(m.group(2));
        UUID   uid;
        try {
            uid = UUID.fromString(m.group(3));
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Cursor id field is not a valid UUID.", e);
        }

        if (!fp.equals(expectedFingerprint)) {
            throw new InvalidCursorException(
                    "Cursor was issued for a different sort order and cannot be replayed here.");
        }

        return new KeysetCursor(fp, Instant.ofEpochMilli(ca), uid);
    }

    /**
     * Computes a sort-order fingerprint from a list of sort fields.
     *
     * <p>The fingerprint is embedded in every cursor so cross-sort-order replay is detectable
     * without server-side cursor storage.
     *
     * @param sortFields resolved sort fields (persistent names, after allow-list check)
     * @return deterministic fingerprint string, e.g. {@code "createdAt:DESC,id:ASC"}
     */
    public static String computeFingerprint(java.util.List<SortField> sortFields) {
        StringBuilder sb = new StringBuilder();
        for (SortField sf : sortFields) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(sf.field()).append(':').append(sf.direction().name());
        }
        return sb.toString();
    }

    private static byte[] hmacSha256(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
