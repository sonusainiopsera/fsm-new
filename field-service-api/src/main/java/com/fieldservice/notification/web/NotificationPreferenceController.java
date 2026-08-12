package com.fieldservice.notification.web;

import com.fieldservice.common.api.ErrorResponse;
import com.fieldservice.common.api.PageResponse;
import com.fieldservice.notification.api.NotificationPreferenceService;
import com.fieldservice.notification.web.dto.NotificationPreferenceDto;
import com.fieldservice.notification.web.dto.UpsertPreferencesRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * REST controller for per-user notification preference management.
 *
 * <p>All endpoints are scoped to a specific user identified by the {@code {userId}} path
 * variable.  Access is allowed only to:
 * <ul>
 *   <li>the user themselves (JWT subject == userId), or</li>
 *   <li>callers holding the {@code ADMIN} role.</li>
 * </ul>
 *
 * <p>When the caller is unauthorised, a generic 403 is returned regardless of whether
 * the target user exists (no existence disclosure).
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/notification-preferences")
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;

    // ── GET ──────────────────────────────────────────────────────────────────

    /**
     * List all explicit notification preferences for the specified user.
     *
     * <p>Returns an empty page when the user has no explicit preferences
     * (all channels are implicitly enabled in that case).
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or #userId.toString() == authentication.name")
    public ResponseEntity<PageResponse<NotificationPreferenceDto>> getPreferences(
            @PathVariable UUID userId,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication,
            UriComponentsBuilder uriBuilder) {

        UUID callerId = extractCallerId(authentication);
        boolean isAdmin = hasAdminRole(authentication);

        Page<NotificationPreferenceDto> page =
                service.getPreferences(userId, callerId, isAdmin, pageable);

        return ResponseEntity.ok(PageResponse.of(page, uriBuilder));
    }

    // ── PUT ──────────────────────────────────────────────────────────────────

    /**
     * Bulk-upsert notification preferences for the specified user.
     *
     * <p>Items are processed atomically within a single transaction.
     * Returns the full current preference list after the operation.
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN') or #userId.toString() == authentication.name")
    public ResponseEntity<PageResponse<NotificationPreferenceDto>> upsertPreferences(
            @PathVariable UUID userId,
            @Valid @RequestBody UpsertPreferencesRequest request,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication,
            UriComponentsBuilder uriBuilder) {

        UUID callerId = extractCallerId(authentication);
        boolean isAdmin = hasAdminRole(authentication);

        Page<NotificationPreferenceDto> page =
                service.upsertPreferences(userId, callerId, isAdmin, request, pageable);

        return ResponseEntity.ok(PageResponse.of(page, uriBuilder));
    }

    // ── Local exception handler ───────────────────────────────────────────────

    /**
     * Catch {@link AccessDeniedException} at the controller level to return a
     * generic 403 with no information about whether the target user exists.
     *
     * <p>The global handler ({@code GlobalExceptionHandler}) also handles this
     * exception; this local handler takes precedence within this controller,
     * allowing the same safe response to be applied without relying on global
     * ordering.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied in NotificationPreferenceController: {}", ex.getMessage());
        return ResponseEntity.status(403)
                .body(ErrorResponse.of("ACCESS_DENIED", "Access denied", null));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID extractCallerId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            // JWT subject is not a UUID — can happen with ADMIN tokens that use
            // a different subject format.  Return null; the service will handle
            // scope enforcement (admin bypass does not require a UUID callerId).
            return null;
        }
    }

    private boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
