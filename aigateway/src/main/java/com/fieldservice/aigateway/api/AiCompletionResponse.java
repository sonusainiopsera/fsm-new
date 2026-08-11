package com.fieldservice.aigateway.api;

/**
 * Immutable result of a text completion call.
 *
 * @param content          the generated text
 * @param model            the model identifier returned by the provider
 * @param promptTokens     tokens consumed by the input (used for cost estimation)
 * @param completionTokens tokens generated in the output (used for cost estimation)
 */
public record AiCompletionResponse(
        String content,
        String model,
        int promptTokens,
        int completionTokens
) {}
