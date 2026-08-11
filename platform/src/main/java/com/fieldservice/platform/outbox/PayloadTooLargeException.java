package com.fieldservice.platform.outbox;

/**
 * Thrown when a serialised event payload exceeds the configured maximum byte length.
 *
 * <p>Oversized payloads fail the enclosing transaction rather than persisting an unbounded
 * JSONB value that could bloat the outbox table and degrade drain performance.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final int actualBytes;
    private final int maxBytes;

    public PayloadTooLargeException(int actualBytes, int maxBytes) {
        super("Event payload is " + actualBytes + " bytes; maximum allowed is " + maxBytes + " bytes.");
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public int getActualBytes() {
        return actualBytes;
    }

    public int getMaxBytes() {
        return maxBytes;
    }
}
