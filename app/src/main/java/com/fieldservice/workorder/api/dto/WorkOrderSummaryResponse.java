package com.fieldservice.workorder.api.dto;

import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only summary of a work order returned by POST /api/v1/work-orders (201) and
 * GET /api/v1/work-orders/{id}.
 */
public record WorkOrderSummaryResponse(
        UUID id,
        @Nullable String reference,
        WorkOrderState state,
        WorkOrderPriority priority,
        String title,
        UUID customerId,
        UUID siteId,
        UUID assignedTechnicianId,
        int version,
        Instant createdAt,
        Instant updatedAt,
        int cumulativeHoldMinutes,
        @Nullable String currentHoldReasonCode,
        @Nullable Instant holdStartedAt,
        @Nullable Instant responseDeadlineAt,
        @Nullable Instant resolutionDeadlineAt,
        @Nullable Instant atRiskAt,
        @Nullable UUID appliedSlaPolicyId
) {}
