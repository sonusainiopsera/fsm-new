package com.fieldservice.copilot.api;

/**
 * Thrown when the grounding context for a work order cannot be assembled
 * (e.g. missing asset, unreadable enrichment context, or an INSUFFICIENT sufficiency verdict).
 *
 * <p>Callers map this to the INSUFFICIENT path and must NOT forward the prompt to the
 * AI provider. Never includes PII in the message.
 */
public class GroundingUnavailableException extends RuntimeException {

    private final SufficiencyVerdict.ReasonCode reasonCode;

    public GroundingUnavailableException(SufficiencyVerdict.ReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public SufficiencyVerdict.ReasonCode getReasonCode() {
        return reasonCode;
    }
}
