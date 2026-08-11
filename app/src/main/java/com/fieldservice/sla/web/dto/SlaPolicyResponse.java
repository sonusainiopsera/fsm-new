package com.fieldservice.sla.web.dto;

import com.fieldservice.sla.SlaPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for SLA policy admin endpoints.
 */
public record SlaPolicyResponse(
        UUID id,
        String priority,
        int responseMinutes,
        int resolutionMinutes,
        BigDecimal atRiskFraction,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean active,
        boolean ratified,
        Integer version
) {
    public static SlaPolicyResponse from(SlaPolicy p) {
        return new SlaPolicyResponse(
                p.id(), p.priority(), p.responseMinutes(), p.resolutionMinutes(),
                p.atRiskFraction(), p.effectiveFrom(), p.effectiveTo(), p.active(), p.ratified(), p.version());
    }

    public static SlaPolicyResponse from(com.fieldservice.domain.sla.SlaPolicy e) {
        return new SlaPolicyResponse(
                e.getId(), e.getPriority(), e.getResponseMinutes(), e.getResolutionMinutes(),
                e.getAtRiskFraction(), e.getEffectiveFrom(), e.getEffectiveTo(),
                e.isActive(), e.isRatified(), e.getVersion());
    }
}
