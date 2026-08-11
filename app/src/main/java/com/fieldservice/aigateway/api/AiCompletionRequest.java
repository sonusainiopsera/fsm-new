package com.fieldservice.aigateway.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Request for a text completion. Callers must supply pre-redacted, pre-grounded prompt content;
 * the gateway does not inspect or modify the prompt.
 *
 * @param userId      identifier of the authenticated user — used for per-user daily cap accounting
 * @param operationId stable identifier for the calling operation (e.g. "copilot-suggestion")
 * @param prompt      pre-redacted prompt content supplied by the calling service
 * @param context     optional key-value context forwarded verbatim in the provider request
 */
public record AiCompletionRequest(
        String userId,
        String operationId,
        String prompt,
        Map<String, Object> context
) {
    public AiCompletionRequest {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(prompt, "prompt");
        if (userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        context = context == null ? Collections.emptyMap() : Collections.unmodifiableMap(context);
    }
}
