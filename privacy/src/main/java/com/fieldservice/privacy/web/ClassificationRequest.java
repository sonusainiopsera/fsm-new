package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.ClassificationTier;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PUT /api/v1/privacy/classifications/{id}.
 *
 * <p>Unknown JSON properties are rejected globally (FAIL_ON_UNKNOWN_PROPERTIES=true).
 */
public record ClassificationRequest(

        @NotNull(message = "tier is required")
        ClassificationTier tier,

        String lawfulBasisNote,

        String handlingNotes,

        @NotNull(message = "version is required for optimistic locking")
        Integer version
) {}
