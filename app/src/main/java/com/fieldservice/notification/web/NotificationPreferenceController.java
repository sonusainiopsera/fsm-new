package com.fieldservice.notification.web;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationPreferenceService;
import com.fieldservice.notification.api.NotificationPreferenceService.PreferenceEntry;
import com.fieldservice.notification.api.NotificationPreferenceService.PreferenceEntryView;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-user notification channel preference endpoints.
 *
 * <h3>Authorization (self-or-ADMIN)</h3>
 * <p>Each method uses Spring Security SpEL to enforce that the caller is either
 * an ADMIN or the same user as the path variable. The method-security expression
 * is evaluated server-side on every request — UI-level hiding is not the protection.
 *
 * <p>A non-admin requesting another user's preferences receives 403 with no
 * indication of whether the target user exists (non-disclosure, BR-19).
 *
 * <h3>Category validation</h3>
 * <p>Category values are validated via Bean Validation ({@code @NotBlank}) and by
 * a database CHECK constraint in V64. Unknown values return 400 before reaching
 * the service layer.
 */
@Tag(name = "Notification Preferences", description = "Per-user notification channel preferences")
@RestController
@RequestMapping("/api/v1/users/{userId}/notification-preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    /**
     * Returns the effective preference list for the given user.
     *
     * <p>Only explicitly stored rows are returned with {@code source=EXPLICIT}.
     * Absent rows (default-on) are not listed here but are treated as all-channels-enabled
     * by fan-out consumers.
     */
    @Operation(
        summary = "Get notification preferences for a user",
        description = "Returns explicit preference rows. Absent rows resolve to all-channels-enabled (default-on).",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Preference list"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden — self or ADMIN only; no existence disclosure")
    })
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or #userId.toString() == authentication.name")
    public ResponseEntity<PagedResponse<PreferenceEntryResponse>> getPreferences(
            @PathVariable UUID userId) {

        List<PreferenceEntryView> views = preferenceService.listPreferences(userId);

        List<PreferenceEntryResponse> data = views.stream()
                .map(PreferenceEntryResponse::from)
                .toList();

        PageMeta  meta  = PageMeta.of(0, data.size(), data.size());
        PageLinks links = PageLinks.none();

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    /**
     * Upserts explicit preferences for the given user.
     *
     * <p>Each entry in the request body is upserted atomically. Unknown JSON properties
     * are rejected with 400. Bean Validation rejects unknown category names before they
     * reach the service layer (additional protection beyond the DB CHECK constraint).
     */
    @Operation(
        summary = "Upsert notification preferences for a user",
        description = "Upserts (category, channel, enabled) triples. Returns the full updated list.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated preference list"),
        @ApiResponse(responseCode = "400", description = "Validation error — unknown category, channel or property"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden — self or ADMIN only; no existence disclosure")
    })
    @PutMapping
    @PreAuthorize("hasRole('ADMIN') or #userId.toString() == authentication.name")
    public ResponseEntity<PagedResponse<PreferenceEntryResponse>> upsertPreferences(
            @PathVariable UUID userId,
            @Valid @RequestBody NotificationPreferenceRequest request) {

        // Validate category against the allow-list before hitting the service
        for (NotificationPreferenceRequest.PreferenceEntry entry : request.preferences()) {
            validateCategory(entry.category());
        }

        UUID actorId = userId; // actor = target unless ADMIN (service logs both)
        List<PreferenceEntry> entries = request.preferences().stream()
                .map(e -> new PreferenceEntry(e.category(), e.channel(), e.enabled()))
                .toList();

        List<PreferenceEntryView> updated = preferenceService.upsertPreferences(userId, entries, actorId);

        List<PreferenceEntryResponse> data = updated.stream()
                .map(PreferenceEntryResponse::from)
                .toList();

        PageMeta  meta  = PageMeta.of(0, data.size(), data.size());
        PageLinks links = PageLinks.none();

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    private static void validateCategory(String category) {
        try {
            com.fieldservice.notification.api.NotificationCategory.valueOf(category);
        } catch (IllegalArgumentException ex) {
            throw new jakarta.validation.ValidationException(
                    "Unknown notification category: " + category);
        }
    }

    /**
     * Response DTO for a single preference row.
     *
     * @param category the notification category
     * @param channel  the channel
     * @param enabled  whether this channel is enabled for the category
     * @param source   DEFAULT (no row, treated as enabled) or EXPLICIT (stored choice)
     */
    public record PreferenceEntryResponse(
            String category,
            NotificationChannel channel,
            boolean enabled,
            NotificationPreferenceService.PreferenceSource source) {

        static PreferenceEntryResponse from(PreferenceEntryView view) {
            return new PreferenceEntryResponse(
                    view.category(), view.channel(), view.enabled(), view.source());
        }
    }
}
