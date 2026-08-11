package com.fieldservice.domain.workorder.lifecycle;

/**
 * Thrown when a {@link TransitionGuard} refuses a lifecycle transition.
 * The API layer maps this to HTTP 422 with code {@link WorkOrderErrorCodes#WORK_ORDER_GUARD_REFUSED}.
 */
public class WorkOrderGuardRefusedException extends RuntimeException {

    private final String guardId;
    private final String code;

    public WorkOrderGuardRefusedException(String guardId, String code, String message) {
        super(message);
        this.guardId = guardId;
        this.code = code;
    }

    public String getGuardCode() {
        return WorkOrderErrorCodes.WORK_ORDER_GUARD_REFUSED;
    }

    public String getGuardId() {
        return guardId;
    }

    public String getCode() {
        return code;
    }
}
