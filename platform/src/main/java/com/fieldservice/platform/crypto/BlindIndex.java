package com.fieldservice.platform.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA-256 blind-index utility for encrypted equality-searchable columns.
 *
 * <p>The blind index is computed over the normalised plaintext using a dedicated index
 * key that is distinct from the envelope data key. Storing the HMAC leaks equality by
 * design — two rows with identical plaintext will have identical blind indices — and is
 * therefore applied only where equality lookup is required.
 *
 * <p>Range, prefix, and sort operations over blind-index columns are unsupported.
 *
 * <p>The index key is configured once at startup via {@link #configure(byte[])} and held
 * in a static volatile field, never logged or serialised.
 */
public final class BlindIndex {

    /** HMAC algorithm — never MD5, SHA-1, or DES. */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static volatile byte[] INDEX_KEY;

    private BlindIndex() {}

    /**
     * Configures the blind-index key. Must be called once at application startup before
     * any call to {@link #compute(String)}.
     *
     * @param rawKeyBytes exactly 32 bytes (256 bits)
     */
    public static void configure(byte[] rawKeyBytes) {
        if (rawKeyBytes == null || rawKeyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "BlindIndex index key must be exactly 32 bytes (256 bits)");
        }
        INDEX_KEY = rawKeyBytes.clone();
    }

    /**
     * Computes the HMAC-SHA-256 blind index for the given plaintext value.
     *
     * @param value normalised plaintext (caller is responsible for normalisation such as
     *              lowercasing email addresses)
     * @return 64-character lowercase hex HMAC, or {@code null} if {@code value} is null
     */
    public static String compute(String value) {
        if (value == null) return null;
        byte[] key = requireKey();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new KeyManagementUnavailableException(
                    "BlindIndex: HMAC computation failed", e);
        }
    }

    private static byte[] requireKey() {
        byte[] k = INDEX_KEY;
        if (k == null) {
            throw new IllegalStateException(
                    "BlindIndex: index key not configured. "
                    + "Ensure EnvelopeEncryptionConfig has been initialised before use.");
        }
        return k;
    }
}
