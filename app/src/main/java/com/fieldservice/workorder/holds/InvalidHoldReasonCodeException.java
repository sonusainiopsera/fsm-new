package com.fieldservice.workorder.holds;

/**
 * Thrown when a HOLD transition supplies a reason code that is absent from the
 * active vocabulary. Maps to HTTP 400 with a field-level error on {@code holdReasonCode}.
 */
public class InvalidHoldReasonCodeException extends RuntimeException {

    private final String code;

    public InvalidHoldReasonCodeException(String code) {
        super("Hold reason code is unknown or inactive: '" + code + "'");
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
