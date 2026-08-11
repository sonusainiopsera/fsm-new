package com.fieldservice.copilot.internal;

import com.fieldservice.copilot.api.SufficiencyVerdict;
import com.fieldservice.workorder.enrichment.WorkOrderEnrichmentContext;
import org.springframework.stereotype.Component;

/**
 * Evaluates whether the assembled grounding context is sufficient to produce a safe AI answer.
 *
 * <p>Rules are applied in order; the first failing rule determines the verdict and reason code.
 * This makes each rule independently testable and the decision path deterministic.
 *
 * <p>Rules (in order):
 * <ol>
 *   <li>Asset identity must be resolved — a prompt without an asset is ungrounded.</li>
 *   <li>Either a non-empty fault description OR at least one prior service record must be present —
 *       without any signal the model would be forced to invent content.</li>
 *   <li>Both asset identity and at least one prior service record present — SUFFICIENT.</li>
 * </ol>
 */
@Component
class GroundingSufficiencyEvaluator {

    record Verdict(SufficiencyVerdict verdict, SufficiencyVerdict.ReasonCode reasonCode) {
        static Verdict sufficient() {
            return new Verdict(SufficiencyVerdict.SUFFICIENT, SufficiencyVerdict.ReasonCode.OK);
        }
    }

    Verdict evaluate(WorkOrderEnrichmentContext ctx) {
        // Rule 1: asset identity required
        if (ctx.asset() == null) {
            return new Verdict(SufficiencyVerdict.INSUFFICIENT, SufficiencyVerdict.ReasonCode.NO_ASSET_IDENTITY);
        }

        boolean hasFaultDescription = ctx.faultDescription() != null
                && !ctx.faultDescription().isBlank();
        boolean hasPriorHistory = !ctx.priorWorkOrders().isEmpty();

        // Rule 2: at least one evidence signal (fault description OR prior history)
        if (!hasFaultDescription && !hasPriorHistory) {
            return new Verdict(SufficiencyVerdict.INSUFFICIENT,
                    SufficiencyVerdict.ReasonCode.NO_PRIOR_SERVICE_HISTORY);
        }

        // Rule 3: fault description alone without prior history is still INSUFFICIENT —
        // we need at least one prior service record to give the model real grounding signal
        if (!hasPriorHistory) {
            return new Verdict(SufficiencyVerdict.INSUFFICIENT,
                    SufficiencyVerdict.ReasonCode.NO_PRIOR_SERVICE_HISTORY);
        }

        // Rule 4: prior history exists but no fault description — still SUFFICIENT because
        // asset history provides grounding even if the current fault is not yet described
        return Verdict.sufficient();
    }
}
