package com.fieldservice.domain.workorder.lifecycle;

/**
 * All events that may trigger a work-order lifecycle transition.
 * Each name is a verb describing the action, not the target state.
 * The transition table is the only place that maps (currentState, event) → nextState.
 */
public enum WorkOrderEvent {
    /** Dispatcher assigns a technician to a NEW work order → ASSIGNED. */
    ASSIGN,
    /** Technician departs for the site from ASSIGNED → EN_ROUTE. */
    DEPART,
    /** Technician starts work on site from EN_ROUTE → IN_PROGRESS. */
    START,
    /** Technician or dispatcher places an active job on hold → ON_HOLD. */
    HOLD,
    /** Technician or dispatcher resumes a held job → IN_PROGRESS. */
    RESUME,
    /** Technician completes field work from IN_PROGRESS → COMPLETED. */
    COMPLETE,
    /** Dispatcher or manager closes a completed job → CLOSED (terminal). */
    CLOSE,
    /** Dispatcher cancels a job from any non-terminal active state → CANCELLED (terminal). */
    CANCEL
}
