package com.fieldservice.privacy.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/v1/privacy/classifications/{id}}.
 *
 * <p>Unknown JSON properties are rejected to prevent mass-assignment vectors (A05).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateClassificationRequest(
        @NotNull ClassificationTier tier,
        String lawfulBasisNote,
        String handlingNotes,
        @NotNull Integer version
) {}
