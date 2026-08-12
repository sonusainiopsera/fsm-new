package com.fieldservice.workorder.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Response body for POST /api/v1/work-orders/{id}/duplicate-of. */
public record DuplicateLinkResponse(
        UUID sourceWorkOrderId,
        String sourceState,
        String cancellationReasonCode,
        UUID targetWorkOrderId,
        Instant linkedAt
) {}
