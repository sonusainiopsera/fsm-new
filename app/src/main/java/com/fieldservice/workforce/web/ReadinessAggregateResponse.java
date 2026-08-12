package com.fieldservice.workforce.web;

import java.math.BigDecimal;
import java.time.Instant;

public record ReadinessAggregateResponse(
        BigDecimal readinessPercent,
        int        completeTechnicians,
        int        activeTechnicians,
        int        gateTarget,
        boolean    gateMet,
        int        blockingTechnicianCount,
        String     definitionVersion,
        Instant    evaluatedAt,
        boolean    applicable
) {}
