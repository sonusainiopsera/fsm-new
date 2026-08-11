package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/** One recurring availability window in a batch request. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AvailabilityWindowRequest(

        @NotNull
        @Min(1) @Max(7)
        Integer dayOfWeek,

        @NotNull
        LocalTime startTime,

        @NotNull
        LocalTime endTime,

        @NotNull
        LocalDate effectiveFrom,

        LocalDate effectiveTo
) {}
