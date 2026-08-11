package com.fieldservice.sla.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request DTO for creating or updating an SLA policy.
 *
 * <p>Unknown JSON properties are rejected (FAIL_ON_UNKNOWN_PROPERTIES semantics) so
 * a client cannot silently pass fields that won't be persisted.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateSlaPolicyRequest(

        @NotBlank
        @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL",
                 message = "priority must be one of LOW, MEDIUM, HIGH, CRITICAL")
        String priority,

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
        Instant effectiveFrom
) {}
