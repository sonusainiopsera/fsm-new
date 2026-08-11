package com.fieldservice.workorder;

/** Stable machine-readable error codes for work-order lifecycle failures. */
public final class WorkOrderErrorCodes {

    public static final String ILLEGAL_TRANSITION  = "WORK_ORDER_ILLEGAL_TRANSITION";
    public static final String GUARD_REFUSED        = "WORK_ORDER_GUARD_REFUSED";
    public static final String VERSION_CONFLICT     = "WORK_ORDER_VERSION_CONFLICT";

    private WorkOrderErrorCodes() {}
}
