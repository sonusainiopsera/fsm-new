package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.UserPreferenceRequest;
import com.fieldservice.identity.api.dto.UserPreferenceResponse;
import com.fieldservice.identity.application.UserPreferencesService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for the authenticated user's own appearance preference.
 *
 * <p>No path or body parameter accepts a user identifier — the subject is derived
 * exclusively from the validated JWT principal so IDOR is structurally impossible
 * through this surface.
 *
 * <p>Idempotency-Key is handled transparently by the platform {@code IdempotencyFilter}.
 */
@RestController
@RequestMapping("/api/v1/users/me/preferences")
public class UserPreferencesController {

    private final UserPreferencesService preferencesService;

    public UserPreferencesController(UserPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    /**
     * Returns the authenticated user's stored and effective appearance preference.
     *
     * <p>A null stored preference resolves to LIGHT so callers always receive an
     * explicit effective value regardless of whether the user has ever set one.
     *
     * @param authentication injected by Spring Security
     * @return 200 with the preference envelope
     */
    @GetMapping
    public ResponseEntity<UserPreferenceResponse> getPreference(Authentication authentication) {
        return ResponseEntity.ok(preferencesService.getPreference(authentication));
    }

    /**
     * Updates the authenticated user's appearance preference.
     *
     * <p>Unknown JSON properties are rejected globally (FAIL_ON_UNKNOWN_PROPERTIES=true).
     * Invalid enum values produce a 400 with a field-level structured error payload via
     * Bean Validation before any persistence occurs.
     *
     * @param request        validated preference payload
     * @param authentication injected by Spring Security
     * @return 200 with the updated preference envelope
     */
    @PutMapping
    public ResponseEntity<UserPreferenceResponse> updatePreference(
            @Valid @RequestBody UserPreferenceRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(preferencesService.updatePreference(request, authentication));
    }
}
