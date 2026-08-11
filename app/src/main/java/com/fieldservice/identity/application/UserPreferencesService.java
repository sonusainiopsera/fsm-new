package com.fieldservice.identity.application;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.api.dto.UpdatePreferencesRequest;
import com.fieldservice.identity.api.dto.UserPreferencesResponse;
import com.fieldservice.identity.domain.AppearancePreference;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service for reading and updating the authenticated user's appearance preference.
 *
 * <p><strong>Self-scope invariant:</strong> the subject is always derived from the
 * authenticated principal via {@link AccessScopeResolver} — no client-supplied user
 * identifier is accepted, closing the IDOR vector (OWASP A01, BR-19).
 *
 * <p><strong>Audit:</strong> {@link AppUser} is {@code @Audited}; updating
 * {@code appearancePreference} automatically writes a revision row to
 * {@code app_user_aud} and a {@code REVINFO} row within the same transaction (BR-21).
 *
 * <p><strong>Data classification:</strong> {@code appearance_preference} is Internal (BR-23).
 */
@Service
public class UserPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(UserPreferencesService.class);

    private final AppUserRepository appUserRepository;
    private final AccessScopeResolver scopeResolver;

    public UserPreferencesService(AppUserRepository appUserRepository,
                                  AccessScopeResolver scopeResolver) {
        this.appUserRepository = appUserRepository;
        this.scopeResolver = scopeResolver;
    }

    /**
     * Returns the appearance preference for the authenticated user.
     *
     * <p>NULL stored preference resolves to {@link AppearancePreference#LIGHT} in the response.
     *
     * @return the user's preferences (never null)
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public UserPreferencesResponse getPreferences() {
        UUID userId = resolveCurrentUserId();
        AppUser user = loadUser(userId);
        return UserPreferencesResponse.of(user.getId(), user.getAppearancePreference());
    }

    /**
     * Updates the appearance preference for the authenticated user.
     *
     * <p>The change is flushed within the same transaction, ensuring the Envers revision
     * and the domain update are atomically committed or rolled back together (BR-21).
     *
     * @param request the validated preference update request
     * @return the updated preferences
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public UserPreferencesResponse updatePreferences(UpdatePreferencesRequest request) {
        UUID userId = resolveCurrentUserId();
        AppUser user = loadUser(userId);

        AppearancePreference previous = user.getAppearancePreference();
        AppearancePreference next = request.appearance();

        user.setAppearancePreference(next);
        appUserRepository.save(user);

        log.info("appearance.preference.changed: userId={}, from={}, to={}",
                userId, previous, next);

        return UserPreferencesResponse.of(user.getId(), user.getAppearancePreference());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID resolveCurrentUserId() {
        return scopeResolver.resolve().userId();
    }

    private AppUser loadUser(UUID userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "AppUser", userId));
    }
}
