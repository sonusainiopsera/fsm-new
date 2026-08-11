package com.fieldservice.domain.workorder.lifecycle;

/**
 * Stable machine-readable error code constants for work-order lifecycle errors.
 * These codes are part of the public API contract — once published they must not
 * be renamed or removed, only deprecated and superseded.
 * Clients MUST branch on {@code code}, never on {@code message} text.
 */
public final class WorkOrderErrorCodes {

    /** The requested event is not defined for the work order's current state. HTTP 409. */
    public static final String WORK_ORDER_ILLEGAL_TRANSITION = "WORK_ORDER_ILLEGAL_TRANSITION";

    /** A lifecycle guard refused the transition. HTTP 422. */
    public static final String WORK_ORDER_GUARD_REFUSED = "WORK_ORDER_GUARD_REFUSED";

    /** Optimistic-lock version conflict — concurrent modification detected. HTTP 409. */
    public static final String WORK_ORDER_VERSION_CONFLICT = "WORK_ORDER_VERSION_CONFLICT";

    private WorkOrderErrorCodes() {}
}
