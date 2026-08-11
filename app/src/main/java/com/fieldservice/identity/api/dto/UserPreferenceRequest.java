package com.fieldservice.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fieldservice.identity.domain.AppearancePreference;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PUT /api/v1/users/me/preferences.
 *
 * <p>Unknown JSON properties are rejected by the global Jackson configuration
 * (FAIL_ON_UNKNOWN_PROPERTIES=true). The preference field is validated against
 * the {@link AppearancePreference} enum at deserialization time.
 */
public record UserPreferenceRequest(
        @NotNull(message = "preference is required")
        AppearancePreference preference) {

    @JsonCreator
    public static UserPreferenceRequest of(
            @JsonProperty("preference") AppearancePreference preference) {
        return new UserPreferenceRequest(preference);
    }
}
