package com.fieldservice.workorder.application;

import java.time.Instant;
import java.util.UUID;

public record WorkOrderCreatedPayload(
        UUID    workOrderId,
        String  reference,
        String  priority,
        UUID    siteId,
        UUID    assignedTechnicianId,
        Instant responseDueAt,
        Instant resolutionDueAt,
        Instant atRiskAt
) {}
