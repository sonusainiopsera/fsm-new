package com.fieldservice.workorder;

/**
 * Thrown when a {@link com.fieldservice.workorder.lifecycle.TransitionGuard} refuses
 * the transition or throws an unexpected exception (fail-closed).
 *
 * <p>Maps to HTTP 422 with code {@link WorkOrderErrorCodes#WORK_ORDER_GUARD_REFUSED}.
 */
public class GuardRefusedException extends RuntimeException {

    private final String guardId;
    private final String code;

    public GuardRefusedException(String guardId, String code, String message) {
        super(message);
        this.guardId = guardId;
        this.code = code;
    }

    public String getGuardId() {
        return guardId;
    }

    public String getCode() {
        return code;
    }
}
