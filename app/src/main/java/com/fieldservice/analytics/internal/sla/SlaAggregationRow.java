package com.fieldservice.analytics.internal.sla;

import java.time.Instant;

/**
 * One row from the SLA aggregation query: per-priority totals for a time window.
 *
 * <p>{@code compliantCount}: work orders closed at or before their committed resolution deadline.
 * Work orders whose {@code resolution_deadline} is NULL are excluded from both compliant and
 * denominator counts — they belong to the POLICY_MISSING degraded segment handled separately.
 *
 * <p>{@code meanResolutionMinutes} and {@code medianResolutionMinutes} include ALL closed work
 * orders in the window, regardless of deadline presence (resolution time is independent of SLA).
 */
public record SlaAggregationRow(
        String  priority,
        String  windowKey,
        long    closedCount,
        long    compliantCount,
        long    breachCount,
        double  meanResolutionMinutes,
        double  medianResolutionMinutes,
        long    totalOverrunMinutes,
        Instant dataAsOf
) {}
