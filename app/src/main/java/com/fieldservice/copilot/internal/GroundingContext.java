package com.fieldservice.copilot.internal;

import com.fieldservice.workorder.enrichment.WorkOrderEnrichmentContext;

/**
 * Package-private wrapper that prevents raw enrichment context from leaking outside the copilot package.
 *
 * <p>This type is intentionally package-private. {@link PromptAssembler} is the only class
 * that constructs an {@link com.fieldservice.aigateway.api.AiCompletionRequest} for copilot,
 * and it always invokes the redactor. Since this type cannot be referenced outside the
 * {@code copilot.internal} package, callers cannot bypass redaction by holding a reference
 * to raw context.
 */
record GroundingContext(WorkOrderEnrichmentContext enrichment) {
    GroundingContext {
        java.util.Objects.requireNonNull(enrichment, "enrichment");
    }
}
