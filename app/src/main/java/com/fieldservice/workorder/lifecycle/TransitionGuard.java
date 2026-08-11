package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;

/**
 * Extension point for pre-transition business rules.
 *
 * <p>Guards are referenced by identifier in the transition table and evaluated in order before
 * the state change is committed. This interface is declared here so the table and service can
 * reference guards by name; concrete implementations are provided in the guards story.
 */
public interface TransitionGuard {

    /** Stable identifier matching the guard name declared in the transition table. */
    String guardId();

    /**
     * Evaluate whether the transition is permissible for the given work order and event.
     *
     * @param workOrder the aggregate being transitioned (read-only in guard context)
     * @param event     the event requesting the transition
     * @return {@link GuardResult.Satisfied} to permit or {@link GuardResult.Refused} to block
     */
    GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event);
}
