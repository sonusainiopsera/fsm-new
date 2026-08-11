package com.fieldservice.sla.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminSlaPolicyRequest(
        @NotBlank @Size(max = 20)
        String priority,

        @NotNull @Positive
        Integer responseMinutes,

        @NotNull @Positive
        Integer resolutionMinutes,

        @NotNull
        @DecimalMin("0.50") @DecimalMax("1.00")
        BigDecimal atRiskFraction,

        @NotNull
        Instant effectiveFrom
) {}
