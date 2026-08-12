package com.fieldservice.notification.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.notification.internal.preference.NotificationPreferenceResponse;
import com.fieldservice.notification.internal.preference.NotificationPreferenceService;
import com.fieldservice.notification.internal.preference.NotificationPreferenceUpdateRequest;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for per-user notification channel preferences (WO-197).
 *
 * <p>Access: self-or-ADMIN enforced at both method-security and row-predicate layers.
 * A non-admin requesting another user's preferences receives a 403 with no existence
 * disclosure (the service throws {@link com.fieldservice.platform.security.ScopedAccessDeniedException}).
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/notification-preferences")
@Tag(name = "Notification Preferences", description = "Per-user notification channel preference management")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @Operation(
        operationId = "getNotificationPreferences",
        summary = "Get effective notification preferences for a user"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    public ResponseEntity<PagedResponse<NotificationPreferenceResponse>> getPreferences(
            @PathVariable UUID userId,
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(preferenceService.getPreferences(userId, pageQuery, request));
    }

    @Operation(
        operationId = "updateNotificationPreferences",
        summary = "Upsert explicit notification preferences for a user"
    )
    @PutMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    public ResponseEntity<PagedResponse<NotificationPreferenceResponse>> updatePreferences(
            @PathVariable UUID userId,
            @Valid @RequestBody NotificationPreferenceUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                preferenceService.updatePreferences(userId, request, httpRequest));
    }
}
