package com.fieldservice.aigateway.api;

/**
 * Result of a non-streaming text completion.
 *
 * @param content          the generated text
 * @param promptTokens     tokens consumed by the input prompt
 * @param completionTokens tokens consumed by the generated output
 * @param complete         true when finish_reason is "stop"; false when truncated or incomplete
 */
public record AiCompletionResponse(
        String content,
        int promptTokens,
        int completionTokens,
        boolean complete
) {}
