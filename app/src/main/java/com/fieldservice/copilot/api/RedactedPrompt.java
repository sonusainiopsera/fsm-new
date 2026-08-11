package com.fieldservice.copilot.api;

import com.fieldservice.aigateway.api.AiCompletionRequest;

import java.util.Objects;

/**
 * The fully-assembled, PII-redacted prompt ready for forwarding to the AI gateway.
 *
 * <p>Produced exclusively by {@code PromptAssembler}. The raw grounding context type
 * is package-private so no caller can obtain an un-redacted {@link AiCompletionRequest}.
 *
 * @param request        the redacted completion request, safe to forward to the AI provider
 * @param groundingBasis machine-readable attribution for WO-082 display
 * @param redactionReport summary of substitutions performed, for the interaction log (WO-084)
 */
public record RedactedPrompt(
        AiCompletionRequest request,
        GroundingBasis groundingBasis,
        RedactionReport redactionReport
) {
    public RedactedPrompt {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(groundingBasis, "groundingBasis");
        Objects.requireNonNull(redactionReport, "redactionReport");
    }
}
