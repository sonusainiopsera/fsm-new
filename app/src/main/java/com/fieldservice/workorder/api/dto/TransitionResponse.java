package com.fieldservice.workorder.api.dto;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Response body for a successful POST /api/v1/work-orders/{id}/transitions.
 *
 * <p>Includes the new state, new version (for the client's next optimistic-lock check),
 * the events now legally available from the new state, and the transition timestamp.
 */
public record TransitionResponse(
        UUID workOrderId,
        WorkOrderState fromState,
        WorkOrderState toState,
        Integer version,
        Set<WorkOrderEvent> legalNextEvents,
        Instant occurredAt
) {}
