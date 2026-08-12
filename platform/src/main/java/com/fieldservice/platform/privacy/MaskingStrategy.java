package com.fieldservice.platform.privacy;

/**
 * Pure masking function for a single field type.
 *
 * <p>Implementations must be:
 * <ul>
 *   <li><strong>Null-safe</strong> — return {@link #REDACTED} for null input.</li>
 *   <li><strong>Idempotent</strong> — applying the strategy twice produces the same output.</li>
 *   <li><strong>Non-reversible</strong> — no recoverable original substring in the output.</li>
 *   <li><strong>Non-throwing</strong> — on malformed input, return {@link #REDACTED}.</li>
 * </ul>
 *
 * <p>Strategies are pure functions — they hold no state and are thread-safe.
 */
@FunctionalInterface
public interface MaskingStrategy {

    /** Fixed redaction token for fully-redacted values. */
    String REDACTED = "[REDACTED]";

    /**
     * Applies the masking strategy to {@code value}.
     *
     * @param value the raw string value to mask (may be null)
     * @return the masked value; never null
     */
    String mask(String value);
}
