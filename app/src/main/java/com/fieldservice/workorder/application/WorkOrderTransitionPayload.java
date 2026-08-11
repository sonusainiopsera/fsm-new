package com.fieldservice.workorder.application;

import java.util.UUID;

/** Outbox event payload describing a work order lifecycle state change. */
public record WorkOrderTransitionPayload(
        UUID workOrderId,
        String fromState,
        String toState,
        String event,
        UUID actorId,
        String reason) {}
