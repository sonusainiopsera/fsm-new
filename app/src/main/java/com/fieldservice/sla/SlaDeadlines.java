package com.fieldservice.sla;

import java.time.Instant;

/**
 * Immutable triple of SLA deadline instants computed for a work order.
 *
 * <p>All three instants are derived from the active {@link SlaPolicy} for the work
 * order's priority at its creation instant.
 */
public record SlaDeadlines(
        Instant responseDueAt,
        Instant resolutionDueAt,
        Instant atRiskAt
) {}
