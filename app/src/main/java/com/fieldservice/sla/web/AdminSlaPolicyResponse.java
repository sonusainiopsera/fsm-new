package com.fieldservice.sla.web;

import com.fieldservice.sla.domain.SlaPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminSlaPolicyResponse(
        UUID       id,
        String     priority,
        int        responseMinutes,
        int        resolutionMinutes,
        BigDecimal atRiskFraction,
        boolean    ratified,
        Instant    effectiveFrom,
        Instant    effectiveTo,
        boolean    active,
        Instant    createdAt,
        Instant    updatedAt,
        String     updatedBy,
        int        version
) {
    public static AdminSlaPolicyResponse from(SlaPolicy p) {
        return new AdminSlaPolicyResponse(
                p.getId(),
                p.getPriority(),
                p.getResponseMinutes(),
                p.getResolutionMinutes(),
                p.getAtRiskFraction(),
                p.isRatified(),
                p.getEffectiveFrom(),
                p.getEffectiveTo(),
                p.isActive(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getUpdatedBy(),
                p.getVersion() != null ? p.getVersion() : 0
        );
    }
}
