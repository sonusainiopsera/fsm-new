package com.fieldservice.platform.api;

/**
 * Thrown when a downstream provider (external API, messaging system, etc.) is
 * degraded or unavailable.
 * Maps to HTTP 503 with code {@link ErrorCode#PROVIDER_DEGRADED}.
 */
public class ProviderDegradedException extends ApiException {

    public ProviderDegradedException(String provider, String reason) {
        super(ErrorCode.PROVIDER_DEGRADED, "Provider '" + provider + "' is unavailable: " + reason);
    }

    public ProviderDegradedException(String message) {
        super(ErrorCode.PROVIDER_DEGRADED, message);
    }

    public ProviderDegradedException(String message, Throwable cause) {
        super(ErrorCode.PROVIDER_DEGRADED, message, cause);
    }
}
