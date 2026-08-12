package com.fieldservice.privacy.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record RectificationRequest(
        @NotNull UUID dsarRequestId,
        @NotEmpty @Valid List<CorrectionDto> corrections,
        String note) {

    public record CorrectionDto(
            @NotNull String entityName,
            @NotNull String fieldName,
            @NotNull String newValue) {}
}
