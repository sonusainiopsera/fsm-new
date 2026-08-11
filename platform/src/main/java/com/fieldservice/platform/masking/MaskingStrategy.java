package com.fieldservice.platform.masking;

/**
 * Pure function that converts a plaintext value into a masked representation.
 *
 * <p>Implementations must be:
 * <ul>
 *   <li><b>Null-safe</b> — return {@code null} if the input is {@code null}.</li>
 *   <li><b>Idempotent</b> — applying the strategy twice must produce the same result
 *       as applying it once, so a masked value is not double-masked into garbage.</li>
 *   <li><b>Non-reversible</b> — the output must not contain a recoverable original
 *       substring for Confidential strategies, and must be the fixed redaction token
 *       for Restricted strategies.</li>
 *   <li><b>Side-effect-free</b> — no I/O, no logging, no mutation of shared state.</li>
 * </ul>
 *
 * <p>If a strategy implementation encounters a malformed input (e.g. a string that
 * does not look like an email address) it must return the
 * {@link PiiMasker#REDACTION_TOKEN} rather than propagating an exception.
 */
@FunctionalInterface
public interface MaskingStrategy {

    /**
     * Masks the given value.
     *
     * @param value the plaintext value; may be {@code null}
     * @return the masked representation, or {@code null} if {@code value} is {@code null}
     */
    String mask(String value);
}
