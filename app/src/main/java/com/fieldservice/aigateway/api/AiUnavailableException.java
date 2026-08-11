package com.fieldservice.aigateway.api;

/**
 * Thrown when the AI provider is unreachable, times out, the circuit breaker is open,
 * or the feature flag is disabled. Maps to HTTP 503 with code AI_PROVIDER_UNAVAILABLE.
 *
 * <p>The message must never contain provider-internal details, upstream error text,
 * stack traces, credentials or prompt content.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
