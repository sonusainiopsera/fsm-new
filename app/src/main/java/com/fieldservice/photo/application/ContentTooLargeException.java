package com.fieldservice.photo.application;

/** Thrown when the declared content length exceeds the configured maximum. */
public class ContentTooLargeException extends RuntimeException {

    private final long submitted;
    private final long maxBytes;

    public ContentTooLargeException(long submitted, long maxBytes) {
        super("Declared content length " + submitted + " bytes exceeds maximum of " + maxBytes + " bytes");
        this.submitted = submitted;
        this.maxBytes  = maxBytes;
    }

    public long getSubmitted() { return submitted; }
    public long getMaxBytes()  { return maxBytes; }
}
