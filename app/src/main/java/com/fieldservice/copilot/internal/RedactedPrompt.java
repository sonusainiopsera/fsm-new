package com.fieldservice.copilot.internal;

import com.fieldservice.copilot.api.GroundingBasis;

/**
 * A fully redacted, grounded prompt ready for delivery to the AI gateway.
 * This type is package-private so no caller can obtain a redacted prompt without
 * going through {@link PromptAssembler}.
 */
record RedactedPrompt(
        String systemPrompt,
        String userPrompt,
        GroundingBasis basis,
        RedactionReport redactionReport) {
}
