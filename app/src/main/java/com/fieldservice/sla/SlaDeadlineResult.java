package com.fieldservice.sla;

import java.time.Instant;

/**
 * Immutable result of an SLA deadline calculation.
 *
 * @param responseDueAt          when a first response is required
 * @param resolutionDueAt        when the job must be resolved (SLA clock only, no pauses)
 * @param atRiskAt               when the job enters the at-risk window
 * @param effectiveResolutionDueAt resolution deadline adjusted for accrued clock-pause time
 */
public record SlaDeadlineResult(
        Instant responseDueAt,
        Instant resolutionDueAt,
        Instant atRiskAt,
        Instant effectiveResolutionDueAt
) {}
