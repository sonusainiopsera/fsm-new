package com.fieldservice.platform.api;

/**
 * Thrown when the AI provider is unavailable, timed out, circuit-broken, or the
 * feature flag is disabled.
 * Maps to HTTP 503 with code {@link ErrorCode#AI_PROVIDER_UNAVAILABLE}.
 *
 * <p>The {@code cause} is logged internally; it is never surfaced in the HTTP response.
 */
public class AiUnavailableException extends ApiException {

    public AiUnavailableException(String safeMessage) {
        super(ErrorCode.AI_PROVIDER_UNAVAILABLE, safeMessage);
    }

    public AiUnavailableException(String safeMessage, Throwable internalCause) {
        super(ErrorCode.AI_PROVIDER_UNAVAILABLE, safeMessage, internalCause);
    }
}
