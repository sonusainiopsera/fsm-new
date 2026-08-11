package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/** Request body for POST /api/v1/technicians/{id}/absences. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AbsenceRequest(

        @NotNull
        Instant startsAt,

        @NotNull
        Instant endsAt,

        @NotNull
        @Pattern(regexp = "ANNUAL_LEAVE|SICK_LEAVE|TRAINING|PUBLIC_HOLIDAY|OTHER",
                message = "must be one of ANNUAL_LEAVE, SICK_LEAVE, TRAINING, PUBLIC_HOLIDAY, OTHER")
        String reason
) {}
