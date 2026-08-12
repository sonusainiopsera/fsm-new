package com.fieldservice.sla.api.dto;

import java.util.UUID;

/**
 * JSON payload carried in each SLA alert SSE event frame.
 *
 * <p>Fields not applicable to a given event type are null and omitted from JSON
 * serialisation (Jackson {@code NON_NULL} configuration applies).
 *
 * <p>For {@code SLA_AT_RISK}: advisory=true, minutesRemaining populated,
 * overrunMinutes and reasonCode are null.
 * For {@code SLA_BREACHED}: advisory=false, overrunMinutes populated,
 * minutesRemaining and triggerReason may be null.
 *
 * <p>No PII beyond what the subscriber is entitled to see; no raw entity fields.
 * Free text fields are length-capped at source.
 */
public record SlaAlertEventData(
        UUID workOrderId,
        String workOrderReference,
        String priority,
        String state,
        Integer minutesRemaining,
        String triggerReason,
        String projectionBasis,
        boolean advisory,
        Integer overrunMinutes,
        String reasonCode
) {}
