package com.fieldservice.identity.application;

import com.fieldservice.identity.api.dto.UserPreferenceRequest;
import com.fieldservice.identity.api.dto.UserPreferenceResponse;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.domain.AppearancePreference;
import com.fieldservice.identity.domain.UserPreferenceAudit;
import com.fieldservice.identity.domain.UserPreferenceAuditRepository;
import com.fieldservice.platform.api.exception.ForbiddenException;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages per-account appearance preference reads and writes.
 *
 * <p>Authorization is deliberately narrow: the subject is always derived from the
 * authenticated JWT principal, never from a client-supplied identifier. This closes
 * the IDOR vector (A01, BR-19). There is no path to read or write another user's
 * preference — even for ADMIN callers — because the preference is a personal
 * display choice, not a managed resource.
 *
 * <p>Both methods require authentication. The self-scope guard inside each method
 * provides defence-in-depth beyond the method-security annotation.
 *
 * <p>Every mutation writes a {@link UserPreferenceAudit} record in the same
 * {@code @Transactional} boundary so no preference change escapes without its audit
 * trail (BR-21). Envers also captures the change on the {@link AppUser} entity
 * automatically because {@link AppUser} is {@code @Audited}.
 */
@Service
public class UserPreferencesService {

    private final AppUserRepository            userRepository;
    private final UserPreferenceAuditRepository auditRepository;

    public UserPreferencesService(AppUserRepository userRepository,
                                  UserPreferenceAuditRepository auditRepository) {
        this.userRepository  = userRepository;
        this.auditRepository = auditRepository;
    }

    /**
     * Returns the appearance preference for the authenticated user.
     *
     * <p>A null stored preference resolves to LIGHT in the response so new accounts
     * always see an explicit effective value.
     *
     * @param authentication Spring Security authentication carrying the JWT subject
     * @return the user's current and effective preference
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public UserPreferenceResponse getPreference(Authentication authentication) {
        UUID userId = resolveUserId(authentication);
        AppUser user = loadUser(userId);
        return UserPreferenceResponse.of(user.getId(), user.getAppearancePreference());
    }

    /**
     * Updates the appearance preference for the authenticated user.
     *
     * <p>The mutation and its {@link UserPreferenceAudit} record are committed in one
     * transaction. A rolled-back transaction leaves neither an Envers revision nor an
     * audit row.
     *
     * @param request         validated preference payload
     * @param authentication  Spring Security authentication carrying the JWT subject
     * @return the updated preference
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public UserPreferenceResponse updatePreference(UserPreferenceRequest request,
                                                    Authentication authentication) {
        UUID userId = resolveUserId(authentication);
        AppUser user = loadUser(userId);

        AppearancePreference before = user.getAppearancePreference();
        AppearancePreference after  = request.preference();

        user.setAppearancePreference(after);
        userRepository.save(user);

        String actorRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse(null);

        UserPreferenceAudit audit = UserPreferenceAudit.record(
                userId,
                "appearance_preference",
                before != null ? before.name() : null,
                after.name(),
                userId,
                actorRole,
                MDC.get("traceId"));
        auditRepository.save(audit);

        return UserPreferenceResponse.of(user.getId(), after);
    }

    // ---- Helpers ---------------------------------------------------------------

    private static UUID resolveUserId(Authentication authentication) {
        String subject = authentication.getName();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("JWT subject is not a valid user identifier");
        }
    }

    private AppUser loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenException(
                        "User not found or not accessible — no existence disclosure"));
    }
}
