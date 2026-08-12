package com.fieldservice.workforce.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReadinessSnapshotResponse(
        UUID       id,
        String     isoWeek,
        BigDecimal readinessPercent,
        int        completeTechnicians,
        int        activeTechnicians,
        boolean    gateMet,
        String     definitionVersion,
        Instant    generatedAt
) {}
