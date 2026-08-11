package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

/**
 * Composite key for a work-order lifecycle transition lookup.
 * Two keys are equal when both {@code fromState} and {@code event} are equal;
 * Java records provide this automatically.
 */
public record TransitionKey(WorkOrderState fromState, WorkOrderEvent event) {}
