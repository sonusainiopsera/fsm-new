package com.fieldservice.workorder.lifecycle;

/** Reason codes required when placing a work order ON_HOLD via the HOLD event. */
public enum WorkOrderHoldReasonCode {
    WAITING_FOR_PARTS,
    WAITING_FOR_ACCESS,
    WAITING_FOR_CUSTOMER,
    TECHNICIAN_UNAVAILABLE,
    SAFETY_CONCERN,
    OTHER
}
