package com.fieldservice.dispatch.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbox event payload for the TECHNICIAN_ASSIGNED domain event.
 *
 * <p>Must not contain PII beyond identifiers per the event payload contract.
 */
public record TechnicianAssignedPayload(
        UUID       workOrderId,
        UUID       technicianId,
        UUID       assignmentId,
        Integer    recommendationRank,
        BigDecimal recommendationScore,
        boolean    override
) {}
