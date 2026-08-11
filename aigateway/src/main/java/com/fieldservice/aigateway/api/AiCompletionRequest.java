package com.fieldservice.aigateway.api;

import java.util.UUID;

/**
 * Immutable request for a text completion.
 *
 * <p>Callers (WO-081 through WO-085) are responsible for grounding and
 * redacting content before passing it here. No PII or credential material
 * may appear in these fields.
 *
 * @param userId        caller identity — used for per-user cap enforcement; never included in outbound HTTP
 * @param systemPrompt  pre-grounded system instruction (operator-defined, not user-supplied)
 * @param userMessage   pre-redacted user content
 * @param maxTokens     upper bound on completion tokens; 0 means use the configured default
 */
public record AiCompletionRequest(
        UUID userId,
        String systemPrompt,
        String userMessage,
        int maxTokens
) {
    public AiCompletionRequest {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
    }
}
