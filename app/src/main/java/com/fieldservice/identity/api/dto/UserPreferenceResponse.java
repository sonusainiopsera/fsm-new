package com.fieldservice.identity.api.dto;

import com.fieldservice.identity.domain.AppearancePreference;

import java.util.UUID;

/**
 * Response body for GET and PUT /api/v1/users/me/preferences.
 *
 * <p>{@code effectivePreference} is always non-null: a null stored preference resolves
 * to {@link AppearancePreference#LIGHT} so new accounts always see the default explicitly.
 */
public record UserPreferenceResponse(
        UUID userId,
        AppearancePreference storedPreference,
        AppearancePreference effectivePreference) {

    public static UserPreferenceResponse of(UUID userId, AppearancePreference stored) {
        AppearancePreference effective = (stored != null) ? stored : AppearancePreference.LIGHT;
        return new UserPreferenceResponse(userId, stored, effective);
    }
}
