package com.fieldservice.workorder.api.dto;

import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only summary of a work order returned by GET /api/v1/work-orders/{id}.
 */
public record WorkOrderSummaryResponse(
        UUID id,
        WorkOrderState state,
        WorkOrderPriority priority,
        String title,
        UUID customerId,
        UUID siteId,
        UUID assignedTechnicianId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
