package com.fieldservice.workforce.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Weekly snapshot response for the trend endpoint (AC-6). */
public record ReadinessSnapshotResponse(
        UUID id,
        String isoWeek,
        BigDecimal readinessPercent,
        int completeTechnicians,
        int activeTechnicians,
        boolean gateMet,
        String definitionVersion,
        Instant generatedAt,
        boolean applicable
) {}
