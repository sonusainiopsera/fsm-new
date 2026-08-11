package com.fieldservice.platform.exception;

/**
 * Thrown when an upstream provider (LLM, travel-time, notification) is unavailable
 * or has exceeded its timeout budget.
 *
 * <p>Maps to HTTP 503 Service Unavailable.
 */
public class ProviderDegradedException extends RuntimeException {

    private final String providerName;

    public ProviderDegradedException(String providerName, String reason) {
        super(providerName + " is currently unavailable: " + reason);
        this.providerName = providerName;
    }

    public ProviderDegradedException(String providerName, Throwable cause) {
        super(providerName + " is currently unavailable.", cause);
        this.providerName = providerName;
    }

    public String getProviderName() { return providerName; }
}
