package com.fieldservice.copilot.api;

/**
 * Verdict from {@code GroundingSufficiencyEvaluator} indicating whether the assembled
 * context is rich enough to ground an AI answer.
 */
public enum SufficiencyVerdict {

    /** Context contains asset identity and at least one prior service record or a non-empty fault classification. */
    SUFFICIENT,

    /**
     * Context does not meet the minimum grounding criteria.
     * Callers must refuse to forward the prompt to the AI provider rather than risk a fabricated answer.
     */
    INSUFFICIENT;

    /**
     * Reason code paired with the verdict for logging and the refusal message surfaced to the caller.
     */
    public enum ReasonCode {
        /** Asset identity could not be resolved (no {@code asset_id} on the work order). */
        NO_ASSET_IDENTITY,
        /** Asset identity resolved but no prior service history is available. */
        NO_PRIOR_SERVICE_HISTORY,
        /** Fault description is absent or blank — no structured signal to ground on. */
        NO_FAULT_DESCRIPTION,
        /** All grounding criteria satisfied. */
        OK
    }
}
