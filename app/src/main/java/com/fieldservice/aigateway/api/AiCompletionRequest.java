package com.fieldservice.aigateway.api;

import java.util.List;

/**
 * Provider-agnostic completion request.
 * Content must be pre-redacted and pre-grounded by the caller before this record is constructed.
 * No provider-specific fields or URLs appear here.
 */
public record AiCompletionRequest(
        String userId,
        String systemPrompt,
        List<AiMessage> messages,
        int maxTokens) {

    public record AiMessage(Role role, String content) {
        public enum Role { SYSTEM, USER, ASSISTANT }
    }
}
