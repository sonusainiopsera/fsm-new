package com.fieldservice.workorder.api.dto;

import com.fieldservice.domain.workorder.WorkOrderPriority;
import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Board-projection DTO for paginated work order search results.
 *
 * <p>Contains only the fields needed for the dispatcher board, technician job list,
 * and customer portal. Full work order details are fetched via
 * {@code GET /api/v1/work-orders/{id}}.
 */
public record WorkOrderBoardRow(
        UUID id,
        String title,
        WorkOrderState state,
        WorkOrderPriority priority,
        UUID customerId,
        UUID siteId,
        @Nullable UUID assignedTechnicianId,
        @Nullable Instant resolutionDeadline,
        boolean atRisk,
        int cumulativeHoldMinutes,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
