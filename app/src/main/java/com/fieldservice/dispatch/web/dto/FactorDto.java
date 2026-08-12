package com.fieldservice.dispatch.web.dto;

/**
 * Per-factor scoring breakdown in a recommendation response.
 */
public record FactorDto(
        String  factorCode,
        double  rawValue,
        double  normalisedValue,
        double  weight,
        double  weightedContribution,
        String  explanation,
        boolean degraded) {
}
