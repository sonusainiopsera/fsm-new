package com.fieldservice.workforce.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregate readiness summary response (AC-3, AC-4, API contract).
 *
 * <p>{@code applicable=false} when no active technicians exist (zero-denominator guard).
 * {@code readinessPercent=null} in that case.
 */
public record ReadinessSummaryResponse(
        BigDecimal readinessPercent,
        int completeTechnicians,
        int activeTechnicians,
        int gateTarget,
        boolean gateMet,
        int blockingTechnicianCount,
        String definitionVersion,
        Instant evaluatedAt,
        boolean applicable
) {}
