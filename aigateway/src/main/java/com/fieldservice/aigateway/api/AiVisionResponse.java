package com.fieldservice.aigateway.api;

/**
 * Immutable result of a vision-captioning call.
 *
 * @param caption          generated description of the image
 * @param model            the model identifier returned by the provider
 * @param promptTokens     tokens consumed by the input
 * @param completionTokens tokens generated in the output
 */
public record AiVisionResponse(
        String caption,
        String model,
        int promptTokens,
        int completionTokens
) {}
