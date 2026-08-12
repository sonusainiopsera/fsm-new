package com.fieldservice.sla.internal;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of the fields the SLA risk evaluator needs to make a
 * flag/clear decision.
 *
 * <p>All effective deadline fields are pre-computed by the scheduler before building
 * the snapshot, so the evaluator performs no I/O of its own. The scheduler extends
 * raw deadlines by the total accumulated SLA clock pause duration before setting
 * {@code effectiveAtRiskAt} and {@code effectiveResolutionDueAt}.
 *
 * @param workOrderId              the work order being evaluated
 * @param state                    current lifecycle state
 * @param priority                 work order priority (e.g. "CRITICAL")
 * @param createdAt                instant at which the work order was created
 * @param responseDueAt            original response deadline (may be {@code null} for legacy rows)
 * @param effectiveAtRiskAt        at-risk threshold extended by pause duration (may be {@code null})
 * @param effectiveResolutionDueAt resolution deadline extended by pause duration (may be {@code null})
 * @param effectiveResponseDueAt   response deadline extended by pause duration (may be {@code null})
 * @param currentlyPaused          true when the SLA clock is paused right now
 * @param existingOpenFlagType     the flag type currently open ("AT_RISK" / "PROJECTED_OVERRUN"),
 *                                 or {@code null} if no open flag exists
 */
record WorkOrderRiskSnapshot(
        UUID workOrderId,
        WorkOrderState state,
        String priority,
        Instant createdAt,
        Instant responseDueAt,
        Instant effectiveAtRiskAt,
        Instant effectiveResolutionDueAt,
        Instant effectiveResponseDueAt,
        boolean currentlyPaused,
        String existingOpenFlagType
) {}
