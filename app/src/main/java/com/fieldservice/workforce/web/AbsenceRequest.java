package com.fieldservice.workforce.web;

import com.fieldservice.workforce.internal.AbsenceReason;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record AbsenceRequest(
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull AbsenceReason reason
) {}
