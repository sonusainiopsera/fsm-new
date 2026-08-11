package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;

/**
 * Extension point for pre-transition business rules.
 *
 * <p>Guards are referenced by identifier in the transition table and evaluated in order before
 * the state change is committed. This interface is declared here so the table and service can
 * reference guards by name; concrete implementations are provided in the guards story.
 *
 * <p>Guards must be side-effect-free and must not perform network calls; they run inside the
 * transition transaction on the hot dispatch path.
 */
public interface TransitionGuard {

    /** Stable identifier matching the guard name declared in the transition table. */
    String guardId();

    /**
     * Evaluate whether the transition is permissible for the given work order and event.
     *
     * <p>Guards must never fail open: if evaluation throws an unexpected exception, the caller
     * must treat it as a refusal. The {@link TransitionContext} carries request-scoped data
     * (hold reason code, transition instant) that is not on the entity.
     *
     * @param workOrder the aggregate being transitioned (read-only in guard context)
     * @param event     the event requesting the transition
     * @param context   request-scoped context data for this evaluation
     * @return {@link GuardResult.Satisfied} to permit or {@link GuardResult.Refused} to block
     */
    GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event, TransitionContext context);
}
