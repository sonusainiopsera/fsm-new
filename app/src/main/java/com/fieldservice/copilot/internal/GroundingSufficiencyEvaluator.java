package com.fieldservice.copilot.internal;

import org.springframework.stereotype.Component;

/**
 * Evaluates whether a {@link GroundingContext} contains enough evidence to produce a
 * safe, grounded AI response. Rules are declared in order and documented individually
 * so each can be verified in isolation.
 *
 * <dl>
 *   <dt>R1_ASSET_UNRESOLVED</dt>
 *   <dd>No asset is linked to the work order. Without knowing the equipment type
 *       the model cannot produce meaningful diagnostic suggestions.</dd>
 *
 *   <dt>R2_NO_FAULT_CONTEXT</dt>
 *   <dd>No fault description, fault code, or fault category is present. At least one
 *       of these must be populated to give the model a problem statement.</dd>
 *
 *   <dt>R3_THIN_HISTORY_NO_FAULT_CLASSIFICATION</dt>
 *   <dd>There are no prior service records on the asset AND there is no structured
 *       fault classification (both faultCode and faultCategory absent). When history
 *       is empty a structured classification is required to anchor the response.</dd>
 * </dl>
 */
@Component
class GroundingSufficiencyEvaluator {

    SufficiencyResult evaluate(GroundingContext ctx) {
        // R1: asset must be resolved
        if (ctx.assetId() == null) {
            return SufficiencyResult.insufficient("R1_ASSET_UNRESOLVED");
        }

        // R2: at least one fault context field must be present
        boolean hasFaultContext = isPresent(ctx.faultDescription())
                || isPresent(ctx.faultCode())
                || isPresent(ctx.faultCategory());
        if (!hasFaultContext) {
            return SufficiencyResult.insufficient("R2_NO_FAULT_CONTEXT");
        }

        // R3: when there is no prior history, structured classification is required
        boolean hasPriorHistory = !ctx.priorServiceHistory().isEmpty();
        boolean hasStructuredClassification = isPresent(ctx.faultCode()) && isPresent(ctx.faultCategory());
        if (!hasPriorHistory && !hasStructuredClassification) {
            return SufficiencyResult.insufficient("R3_THIN_HISTORY_NO_FAULT_CLASSIFICATION");
        }

        return SufficiencyResult.sufficient();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
