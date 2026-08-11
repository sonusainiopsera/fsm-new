package com.fieldservice.workorder.lifecycle;

/**
 * Events that drive work order lifecycle transitions.
 *
 * <p>Each value corresponds to exactly one arc in the transition table.
 * The same event name may appear in multiple table entries when reachable from
 * different source states (e.g. CANCEL is legal from NEW, ASSIGNED, EN_ROUTE,
 * IN_PROGRESS, and ON_HOLD).
 */
public enum WorkOrderEvent {
    /** Assign a technician; NEW → ASSIGNED. */
    ASSIGN,
    /** Technician departs for the site; ASSIGNED → EN_ROUTE. */
    DEPART,
    /** Work begins on site; ASSIGNED or EN_ROUTE → IN_PROGRESS. */
    START,
    /** Pause in-progress work; IN_PROGRESS → ON_HOLD. */
    HOLD,
    /** Resume paused work; ON_HOLD → IN_PROGRESS. */
    RESUME,
    /** Mark work done; IN_PROGRESS → COMPLETED. */
    COMPLETE,
    /** Administrative close after review; COMPLETED → CLOSED. */
    CLOSE,
    /** Cancel the work order; legal from NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD. */
    CANCEL
}
