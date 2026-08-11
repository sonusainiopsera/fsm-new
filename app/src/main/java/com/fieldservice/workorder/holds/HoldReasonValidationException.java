package com.fieldservice.workorder.holds;

/**
 * Thrown when a HOLD transition supplies a reason code absent from the active vocabulary.
 * Mapped to HTTP 400 by {@code WorkOrderExceptionHandler}.
 */
public class HoldReasonValidationException extends RuntimeException {

    private final String submittedCode;

    public HoldReasonValidationException(String submittedCode) {
        super("Hold reason code is not in the active vocabulary: " + submittedCode);
        this.submittedCode = submittedCode;
    }

    public String getSubmittedCode() { return submittedCode; }
}
