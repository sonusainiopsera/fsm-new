package com.fieldservice.sla;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO record representing a resolved SLA policy.
 *
 * <p>This is the public-facing representation returned from {@link SlaPolicyProvider}
 * and exposed via the admin API. The JPA entity backing this lives in
 * {@code com.fieldservice.domain.sla.SlaPolicy} and must not be exposed outside the
 * sla module.
 */
public record SlaPolicy(
        UUID id,
        String priority,
        int responseMinutes,
        int resolutionMinutes,
        BigDecimal atRiskFraction,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean active,
        Integer version
) {}
