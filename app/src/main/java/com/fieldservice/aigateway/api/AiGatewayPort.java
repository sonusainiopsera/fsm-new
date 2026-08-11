package com.fieldservice.aigateway.api;

import java.util.stream.Stream;

/**
 * Provider-agnostic AI egress port. This is the only interface through which platform code
 * may invoke an external AI provider. All HTTP clients, credentials, resilience decorators
 * and provider-specific types are package-private in {@code com.fieldservice.aigateway.internal}.
 *
 * <p>All callers must handle {@link AiUnavailableException} (503) and
 * {@link AiCapExceededException} (429) and degrade gracefully.
 */
public interface AiGatewayPort {

    /**
     * Sends a single-shot completion request and returns the full response once available.
     * The call is subject to the 10-second hard budget, circuit breaker, bulkhead and retry
     * policies configured in the internal adapter.
     *
     * @throws AiUnavailableException if the provider is unreachable, times out, or the circuit is open
     * @throws AiCapExceededException if the user has exhausted their daily cap
     */
    AiCompletionResponse complete(AiCompletionRequest request);

    /**
     * Streams a completion as a sequence of delta chunks. The caller is responsible for consuming
     * the stream within the 10-second hard budget; an incomplete stream is marked with
     * {@link AiStreamChunk#last()} {@code false} on the terminal element.
     *
     * @throws AiUnavailableException if the provider is unreachable or the circuit is open
     * @throws AiCapExceededException if the user has exhausted their daily cap
     */
    Stream<AiStreamChunk> completeStreaming(AiCompletionRequest request);

    /**
     * Submits an image URL for captioning. The URL must reference internal object storage only;
     * the gateway does not accept or forward caller-supplied URLs.
     *
     * @throws AiUnavailableException if the provider is unreachable or the circuit is open
     * @throws AiCapExceededException if the user has exhausted their daily cap
     */
    AiVisionResponse caption(AiVisionRequest request);
}
