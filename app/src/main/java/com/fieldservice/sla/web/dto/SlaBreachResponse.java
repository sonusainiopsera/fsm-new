package com.fieldservice.sla.web.dto;

import com.fieldservice.sla.SlaBreachDto;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response body for a single SLA breach record.
 */
public record SlaBreachResponse(
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
) {
    public static SlaBreachResponse from(SlaBreachDto dto) {
        return new SlaBreachResponse(
                dto.id(), dto.workOrderId(), dto.breachType(),
                dto.effectiveDeadline(), dto.detectedAt(),
                dto.overrunMinutes(), dto.pausedMinutesExcluded(),
                dto.finalOverrunMinutes(), dto.reasonCode(), dto.reasonNote(),
                dto.attributedBy(), dto.attributedAt());
    }
}
