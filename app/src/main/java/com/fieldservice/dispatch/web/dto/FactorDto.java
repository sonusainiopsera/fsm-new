package com.fieldservice.dispatch.web.dto;

public record FactorDto(
        String factorCode,
        double rawValue,
        double normalisedValue,
        double weight,
        double weightedContribution,
        String explanation,
        boolean degraded
) {}
