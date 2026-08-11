package com.fieldservice.privacy.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/privacy/dsar-requests}.
 *
 * <p>Unknown JSON properties are rejected (global Jackson setting), so
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} is redundant but explicit.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateDsarRequest(
        @NotBlank String requestType,
        @NotBlank String subjectType,
        @NotNull UUID subjectId,
        @Nullable String notes
) {}
