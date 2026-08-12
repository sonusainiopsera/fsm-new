package com.fieldservice.copilot.internal;

/**
 * Thrown when grounding context cannot be assembled for an AI request — either because
 * the work order is inaccessible to the caller, the asset is unresolved, or the available
 * evidence is insufficient to form a safe grounded prompt.
 */
public class GroundingUnavailableException extends RuntimeException {

    private final String reasonCode;

    public GroundingUnavailableException(String reasonCode) {
        super("Grounding context unavailable [" + reasonCode + "]");
        this.reasonCode = reasonCode;
    }

    public GroundingUnavailableException(String reasonCode, Throwable cause) {
        super("Grounding context unavailable [" + reasonCode + "]", cause);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
