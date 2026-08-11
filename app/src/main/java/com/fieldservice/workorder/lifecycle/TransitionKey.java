package com.fieldservice.workorder.lifecycle;

/** Composite key for a lifecycle table lookup: the current state plus the requested event. */
public record TransitionKey(WorkOrderState fromState, WorkOrderEvent event) {}
