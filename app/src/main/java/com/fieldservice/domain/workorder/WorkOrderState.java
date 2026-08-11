package com.fieldservice.domain.workorder;

/**
 * Lifecycle states for a work order.
 *
 * <p>The state vocabulary is enforced at both the database level (CHECK constraint in V1
 * migration) and the application level (this enum). An INSERT or UPDATE using a value
 * not in this list is rejected by the database regardless of how it was issued.
 *
 * <p>Legal transitions are enforced by the transition guard in the WorkOrder service.
 * An illegal transition attempt returns HTTP 409.
 */
public enum WorkOrderState {
    /** Freshly created, not yet assigned to a technician. */
    NEW,
    /** Assigned to a technician; technician notified. */
    ASSIGNED,
    /** Technician is en route to the site. */
    EN_ROUTE,
    /** Technician is on-site performing work. */
    IN_PROGRESS,
    /** Work paused (awaiting parts, waiting for access, etc.). */
    ON_HOLD,
    /** Work complete; pending administrative closure. */
    COMPLETED,
    /** Administratively closed after completion review. */
    CLOSED,
    /** Work order cancelled before completion. */
    CANCELLED
}
