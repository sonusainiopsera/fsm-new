package com.fieldservice.aigateway.api;

/**
 * Result of a vision-captioning call.
 *
 * @param caption     short one-line caption suitable for accessibility alt-text
 * @param description longer structured description of image content
 * @param tokensUsed  total tokens consumed by this call
 */
public record AiVisionResponse(
        String caption,
        String description,
        int tokensUsed
) {}
