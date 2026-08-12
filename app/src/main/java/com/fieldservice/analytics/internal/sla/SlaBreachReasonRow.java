package com.fieldservice.analytics.internal.sla;

/**
 * One row from the breach-by-reason aggregation query: per (priority, reason_code) counts.
 *
 * <p>{@code reasonCode} may be {@code null} when a breach has not yet been attributed;
 * callers render null as {@code "UNATTRIBUTED"}.
 */
public record SlaBreachReasonRow(
        String priority,
        String windowKey,
        String reasonCode,
        long   breachCount,
        long   totalOverrunMinutes
) {}
