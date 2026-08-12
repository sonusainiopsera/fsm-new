package com.fieldservice.portal.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a successfully submitted portal service request.
 *
 * <p>Only the fields relevant to a customer are exposed — internal fields
 * (assignedTechnicianId, appliedSlaPolicyId, version, etc.) are never surfaced here.
 */
public record ServiceRequestResponse(
        UUID    workOrderId,
        String  reference,
        String  state,
        String  origin,
        Instant respondByAt,
        Instant resolveByAt
) {}
