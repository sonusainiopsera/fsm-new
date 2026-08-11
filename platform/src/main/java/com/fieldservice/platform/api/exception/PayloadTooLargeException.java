package com.fieldservice.platform.api.exception;

/**
 * Thrown when a serialised outbox event payload exceeds the configured size limit.
 * Aborts the enclosing transaction — no state change without a bounded event.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final int actualBytes;
    private final int maxBytes;

    public PayloadTooLargeException(int actualBytes, int maxBytes) {
        super("Outbox payload size " + actualBytes + " bytes exceeds maximum " + maxBytes + " bytes");
        this.actualBytes = actualBytes;
        this.maxBytes    = maxBytes;
    }

    public int getActualBytes() { return actualBytes; }
    public int getMaxBytes()    { return maxBytes; }
}
