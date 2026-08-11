package com.fieldservice.workorder.lifecycle;

/** One event per legal move in the work order lifecycle. */
public enum WorkOrderEvent {
    ASSIGN, UNASSIGN, DEPART, START, HOLD, RESUME, COMPLETE, CLOSE, CANCEL
}
