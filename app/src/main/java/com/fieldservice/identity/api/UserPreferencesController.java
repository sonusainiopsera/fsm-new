package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.UpdatePreferencesRequest;
import com.fieldservice.identity.api.dto.UserPreferencesResponse;
import com.fieldservice.identity.application.UserPreferencesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for the authenticated user's appearance preference.
 *
 * <p>The subject is always the authenticated principal — no path or body user identifier
 * is accepted (self-scope invariant, BR-19, IDOR prevention).
 *
 * <p>Idempotency: PUT is idempotent by nature (setting the same preference twice is safe).
 * The platform {@code IdempotencyKeyFilter} is active on the {@code api} profile and
 * deduplicates client retries on the {@code Idempotency-Key} header if present.
 */
@RestController
@RequestMapping("/api/v1/users/me/preferences")
@Tag(name = "User Preferences", description = "Per-account appearance preference management")
public class UserPreferencesController {

    private final UserPreferencesService preferencesService;

    public UserPreferencesController(UserPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @Operation(
            operationId = "getMyPreferences",
            summary = "Get the authenticated user's preferences"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserPreferencesResponse> getPreferences() {
        return ResponseEntity.ok(preferencesService.getPreferences());
    }

    @Operation(
            operationId = "updateMyPreferences",
            summary = "Update the authenticated user's appearance preference"
    )
    @PutMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<UserPreferencesResponse> updatePreferences(
            @Valid @RequestBody UpdatePreferencesRequest request) {
        return ResponseEntity.ok(preferencesService.updatePreferences(request));
    }
}
