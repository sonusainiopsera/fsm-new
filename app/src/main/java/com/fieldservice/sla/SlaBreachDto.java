package com.fieldservice.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight DTO projection of an SLA breach record, shared between the
 * admin service interface and the REST controller.
 */
public record SlaBreachDto(
        UUID id,
        UUID workOrderId,
        String breachType,
        Instant effectiveDeadline,
        Instant detectedAt,
        int overrunMinutes,
        int pausedMinutesExcluded,
        Integer finalOverrunMinutes,
        String reasonCode,
        String reasonNote,
        UUID attributedBy,
        Instant attributedAt
) {}
