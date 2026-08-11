package com.fieldservice.aigateway.api;

import com.fieldservice.platform.api.exception.AiCapExceededException;
import com.fieldservice.platform.api.exception.AiUnavailableException;

/**
 * Provider-agnostic AI egress port.
 *
 * <p>This is the ONLY interface through which the platform makes external AI calls.
 * No implementation class, HTTP client, provider type, or credential may be visible
 * to callers outside the {@code aigateway} module.
 *
 * <p>Callers supply pre-redacted, pre-grounded content — prompt sanitization and
 * PII removal happen before this boundary, never inside it.
 *
 * @throws AiUnavailableException if the provider is unavailable, the circuit is open, or the budget is exceeded
 * @throws AiCapExceededException if the user has exhausted their daily quota
 */
public interface AiGatewayPort {

    /** Synchronous text completion. */
    AiCompletionResponse complete(AiCompletionRequest request);

    /** Streaming text completion — tokens delivered to {@code callback} in order. */
    void completeStreaming(AiCompletionRequest request, AiStreamCallback callback);

    /** Vision captioning for an image already stored in object storage. */
    AiVisionResponse caption(AiVisionRequest request);
}
