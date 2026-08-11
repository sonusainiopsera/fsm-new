package com.fieldservice.platform.api.exception;

/** Thrown when an upstream provider is unavailable or degraded. Maps to HTTP 503. */
public class ProviderDegradedException extends RuntimeException {
    public ProviderDegradedException(String provider) {
        super("Provider unavailable: " + provider);
    }
}
