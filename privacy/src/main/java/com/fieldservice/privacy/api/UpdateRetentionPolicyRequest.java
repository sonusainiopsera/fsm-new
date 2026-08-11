package com.fieldservice.privacy.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code PUT /api/v1/privacy/retention-policies/{id}}.
 *
 * <p>Unknown JSON properties are rejected (A05 mass-assignment protection).
 * The {@code version} field is required for optimistic-locking; the server
 * responds with 409 if the supplied version is stale.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateRetentionPolicyRequest(
        @NotNull @Positive Integer periodValue,
        @NotBlank String periodUnit,
        @NotBlank String disposalMethod,
        boolean legalHold,
        boolean ratified,
        boolean enabled,
        @Nullable String notes,
        @NotNull Integer version
) {}
