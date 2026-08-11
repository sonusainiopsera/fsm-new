package com.fieldservice.aigateway.api;

/** Provider-agnostic completion response. */
public record AiCompletionResponse(
        String content,
        int promptTokens,
        int completionTokens) {

    public int totalTokens() { return promptTokens + completionTokens; }
}
