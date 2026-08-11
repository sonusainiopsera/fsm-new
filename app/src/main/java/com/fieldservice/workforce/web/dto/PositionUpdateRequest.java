package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Request body for POST /api/v1/technicians/{id}/position. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PositionUpdateRequest(

        @NotNull
        @DecimalMin(value = "-90.0")  @DecimalMax(value = "90.0")
        Double latitude,

        @NotNull
        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        Double longitude,

        @NotNull
        Instant capturedAt
) {}
