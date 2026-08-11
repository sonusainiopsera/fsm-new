package com.fieldservice.workforce.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record AvailabilityWindowRequest(
        @Min(1) @Max(7) int dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
