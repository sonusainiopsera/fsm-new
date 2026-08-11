package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrder;

/**
 * Precondition evaluated before a lifecycle transition may proceed.
 * Implementations are wired by id into {@link TransitionDescriptor#guardIds()};
 * concrete implementations live in the guards story.
 *
 * <p>Guards are evaluated left-to-right in the order listed in the descriptor;
 * the first {@link GuardResult.Refused} result short-circuits evaluation and
 * the transition is rejected with {@link WorkOrderErrorCodes#WORK_ORDER_GUARD_REFUSED}.
 *
 * <p>Guards must be stateless and thread-safe.
 */
public interface TransitionGuard {

    /** Unique identifier matching the string stored in {@link TransitionDescriptor#guardIds()}. */
    String id();

    /**
     * Evaluate whether this guard permits the transition.
     *
     * @param workOrder the work order being transitioned
     * @param event     the requested lifecycle event
     * @return {@link GuardResult#satisfied()} to allow, or {@link GuardResult#refused} to block
     */
    GuardResult evaluate(WorkOrder workOrder, WorkOrderEvent event);
}
