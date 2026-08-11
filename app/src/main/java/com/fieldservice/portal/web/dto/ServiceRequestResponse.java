package com.fieldservice.portal.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for POST /api/v1/portal/service-requests (AC-1).
 *
 * <p>Contains the fields needed for the customer to track their request:
 * the work order ID, human-readable reference, current state, origin, and
 * the committed response and resolution deadlines derived from the active SLA policy.
 */
public record ServiceRequestResponse(
        UUID workOrderId,
        String reference,
        String state,
        String origin,
        Instant respondByAt,
        Instant resolveByAt
) {}
