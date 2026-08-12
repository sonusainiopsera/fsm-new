package com.fieldservice.portal.csat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for submitting a CSAT response.
 */
public record CsatResponseRequest(
        @NotNull @Min(1) @Max(5)
        Integer score,

        @Min(0) @Max(10)
        Integer npsScore,

        @Size(max = 1000)
        String comment) {}
