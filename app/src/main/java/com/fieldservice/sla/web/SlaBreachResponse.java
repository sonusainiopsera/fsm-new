package com.fieldservice.sla.web;

import com.fieldservice.sla.SlaBreachReasonCode;
import com.fieldservice.sla.internal.SlaBreachEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * API representation of an SLA breach record.
 */
public record SlaBreachResponse(
        UUID                id,
        UUID                workOrderId,
        String              breachType,
        Instant             effectiveDeadline,
        Instant             detectedAt,
        long                overrunMinutes,
        long                pausedMinutesExcluded,
        Long                finalOverrunMinutes,
        SlaBreachReasonCode reasonCode,
        String              reasonNote,
        UUID                attributedBy,
        Instant             attributedAt
) {
    static SlaBreachResponse from(SlaBreachEntity e) {
        return new SlaBreachResponse(
                e.getId(),
                e.getWorkOrderId(),
                e.getBreachType(),
                e.getEffectiveDeadline(),
                e.getDetectedAt(),
                e.getOverrunMinutes(),
                e.getPausedMinutesExcluded(),
                e.getFinalOverrunMinutes(),
                e.getReasonCode(),
                e.getReasonNote(),
                e.getAttributedBy(),
                e.getAttributedAt());
    }
}
