package com.fieldservice.identity.api.dto;

import com.fieldservice.identity.domain.AppearancePreference;

import java.util.UUID;

/**
 * Response envelope for GET and PUT /api/v1/users/me/preferences.
 *
 * <p>{@code effectiveAppearance} is the resolved concrete appearance after applying the
 * null-to-LIGHT fallback rule. {@code storedPreference} is the raw stored value (may be null
 * for new accounts), allowing the client to distinguish "no preference set" from LIGHT chosen.
 */
public record UserPreferencesResponse(
        UUID userId,
        AppearancePreference storedPreference,
        AppearancePreference effectiveAppearance
) {
    /** Resolves the effective appearance: null → LIGHT. */
    public static UserPreferencesResponse of(UUID userId, AppearancePreference stored) {
        AppearancePreference effective = stored != null ? stored : AppearancePreference.LIGHT;
        return new UserPreferencesResponse(userId, stored, effective);
    }
}
