package com.fieldservice.aigateway.api;

/** Provider-agnostic vision captioning response. */
public record AiVisionResponse(
        String caption,
        int tokensUsed) {}
