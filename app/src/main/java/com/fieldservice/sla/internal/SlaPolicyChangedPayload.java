package com.fieldservice.sla.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbox event payload for {@code SLA_POLICY_CHANGED} events.
 *
 * <p>Contains only non-personal public fields.  The before/after pattern
 * allows consumers to detect which specific dimensions changed without
 * fetching the policy again.
 */
public record SlaPolicyChangedPayload(
        UUID       policyId,
        String     priority,
        int        beforeResponseMinutes,
        int        beforeResolutionMinutes,
        BigDecimal beforeAtRiskFraction,
        boolean    beforeRatified,
        int        afterResponseMinutes,
        int        afterResolutionMinutes,
        BigDecimal afterAtRiskFraction,
        boolean    afterRatified
) {}
