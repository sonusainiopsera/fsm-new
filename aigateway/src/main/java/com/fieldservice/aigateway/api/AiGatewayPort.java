package com.fieldservice.aigateway.api;

import java.util.function.Consumer;

/**
 * Single egress point for all AI provider calls in the platform.
 *
 * <p>Callers supply pre-grounded, pre-redacted content; the implementation
 * applies feature-flag enforcement, usage-cap checks, resilience decorators
 * and egress controls before any network call is made.
 *
 * <p>Implementations must never surface provider names, credentials,
 * raw HTTP error text, or prompt content in thrown exceptions or logs.
 */
public interface AiGatewayPort {

    /**
     * Synchronous text completion.
     *
     * @throws com.fieldservice.platform.api.AiUnavailableException    on timeout, circuit open, or feature disabled
     * @throws com.fieldservice.platform.api.AiDailyCapExceededException when the per-user daily cap is reached
     */
    AiCompletionResponse complete(AiCompletionRequest request);

    /**
     * Streaming text completion — each chunk is delivered to {@code chunkConsumer} as it arrives.
     * Callers must treat the stream as complete only when a chunk with {@link AiStreamChunk#isLast()}
     * is received. If the connection resets mid-stream, an {@link AiStreamChunk} with
     * {@link AiStreamChunk#isError()} is emitted and no further chunks follow.
     *
     * @throws com.fieldservice.platform.api.AiUnavailableException    before any chunk is emitted if the call cannot start
     * @throws com.fieldservice.platform.api.AiDailyCapExceededException before any chunk is emitted if the cap is exceeded
     */
    void completeStreaming(AiCompletionRequest request, Consumer<AiStreamChunk> chunkConsumer);

    /**
     * Vision captioning — returns a textual description of the supplied image.
     *
     * @throws com.fieldservice.platform.api.AiUnavailableException    on provider error or feature disabled
     * @throws com.fieldservice.platform.api.AiDailyCapExceededException when the per-user daily cap is reached
     */
    AiVisionResponse caption(AiVisionRequest request);
}
