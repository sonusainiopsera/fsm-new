package com.fieldservice.sla.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request DTO for direct PUT update of an SLA policy row.
 *
 * <p>Unlike {@link CreateSlaPolicyRequest}, this request carries a {@code version} field
 * for optimistic-lock conflict detection (returns 409 on mismatch) and a {@code ratified}
 * flag to mark a seeded placeholder as stakeholder-approved.
 *
 * <p>Unknown JSON properties are rejected to prevent mass-assignment.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@ValidSlaPolicy
public record UpdateSlaPolicyRequest(

        @NotNull
        @Positive(message = "responseMinutes must be positive")
        Integer responseMinutes,

        @NotNull
        @Positive(message = "resolutionMinutes must be positive")
        Integer resolutionMinutes,

        @NotNull
        @DecimalMin(value = "0.50", message = "atRiskFraction must be >= 0.50")
        @DecimalMax(value = "1.00", message = "atRiskFraction must be <= 1.00")
        BigDecimal atRiskFraction,

        @NotNull
        Boolean ratified,

        @NotNull
        Integer version
) {}
