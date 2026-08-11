package com.fieldservice.workorder.lifecycle;

/**
 * Strategy for a named lifecycle guard. Implementations are discovered by id and
 * evaluated in the order declared in {@link TransitionDescriptor#guardIds()}.
 */
public interface TransitionGuard {
    String guardId();
    GuardResult evaluate(WorkOrderState fromState, WorkOrderEvent event, Object context);
}
