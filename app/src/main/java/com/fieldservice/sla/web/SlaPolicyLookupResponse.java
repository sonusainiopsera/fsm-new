package com.fieldservice.sla.web;

import com.fieldservice.sla.domain.SlaPolicy;

import java.math.BigDecimal;

/**
 * Dispatcher-facing SLA policy lookup response — contains only the fields
 * needed to preview deadlines before submission. Excludes admin audit fields.
 */
public record SlaPolicyLookupResponse(
        String     priority,
        int        responseMinutes,
        int        resolutionMinutes,
        BigDecimal atRiskFraction
) {
    public static SlaPolicyLookupResponse from(SlaPolicy p) {
        return new SlaPolicyLookupResponse(
                p.getPriority(),
                p.getResponseMinutes(),
                p.getResolutionMinutes(),
                p.getAtRiskFraction());
    }
}
