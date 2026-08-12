package com.fieldservice.platform.crypto;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Computes a deterministic HMAC-SHA-256 blind index over normalised plaintext.
 *
 * <h2>Purpose</h2>
 * Encrypted columns are not directly searchable.  A blind index stores
 * {@code HMAC-SHA-256(normalise(plaintext), indexKey)} alongside the ciphertext so that
 * exact equality lookups can be executed without decrypting the whole table.
 *
 * <h2>Index key</h2>
 * The index key is injected from {@code app.encryption.blind-index-key-base64} and
 * <strong>must be distinct from the data-encryption key</strong>.  Using the data key
 * for both roles would allow cross-correlation attacks against the blind index.
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Equality lookup only — range and prefix queries are not supported.</li>
 *   <li>HMAC leaks equality by design: two records with the same email produce
 *       the same index value, enabling equality confirmation by an adversary
 *       who obtains the index column.  This is an accepted trade-off documented in
 *       docs/security/encryption-design.md §blind-index-trade-offs.</li>
 * </ul>
 *
 * <h2>Collision handling</h2>
 * Callers must verify a candidate row by decrypting the matched ciphertext rather than
 * trusting the index alone.  The 256-bit output makes HMAC collisions computationally
 * infeasible.
 */
public final class BlindIndex {

    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final SecretKey indexKey;

    public BlindIndex(SecretKey indexKey) {
        this.indexKey = indexKey;
    }

    public BlindIndex(byte[] rawIndexKey) {
        this.indexKey = new SecretKeySpec(rawIndexKey, "HmacSHA256");
    }

    /**
     * Returns the hex-encoded HMAC-SHA-256 of {@code normalise(plaintext)}.
     * Returns {@code null} when {@code plaintext} is null or blank (so that
     * nullable column constraints behave predictably).
     */
    public String compute(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        String normalised = normalise(plaintext);
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(indexKey);
            byte[] hmac = mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("BlindIndex HMAC computation failed", e);
        }
    }

    /** Normalise: trim whitespace and lowercase for case-insensitive equality. */
    static String normalise(String value) {
        return value.strip().toLowerCase();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
