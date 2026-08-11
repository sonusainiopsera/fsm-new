package com.fieldservice.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

/**
 * Composite key into the transition table: the current state plus the requested event.
 */
public record TransitionKey(WorkOrderState fromState, WorkOrderEvent event) {}
