package com.fieldservice.workforce.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionRequest(
        @NotNull @DecimalMin("-90")  @DecimalMax("90")  BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull @Positive Integer accuracyMetres,
        @NotNull Instant capturedAt
) {}
