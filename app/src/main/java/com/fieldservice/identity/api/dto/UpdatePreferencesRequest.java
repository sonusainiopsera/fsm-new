package com.fieldservice.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fieldservice.identity.domain.AppearancePreference;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PUT /api/v1/users/me/preferences.
 *
 * <p>Jackson will throw {@link com.fasterxml.jackson.databind.exc.InvalidFormatException}
 * (mapped to 400 VALIDATION_FAILED) if {@code appearance} contains a value outside the
 * {@link AppearancePreference} enum vocabulary.
 *
 * <p>Unknown JSON properties are rejected (FAIL_ON_UNKNOWN_PROPERTIES=true on the ObjectMapper)
 * to prevent mass-assignment (OWASP A08).
 */
public record UpdatePreferencesRequest(
        @NotNull(message = "appearance is required") AppearancePreference appearance
) {
    @JsonCreator
    public UpdatePreferencesRequest(@JsonProperty("appearance") AppearancePreference appearance) {
        this(appearance);
    }
}
