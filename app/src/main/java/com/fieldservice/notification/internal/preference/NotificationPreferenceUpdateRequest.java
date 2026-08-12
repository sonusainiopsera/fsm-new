package com.fieldservice.notification.internal.preference;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Request body for PUT /api/v1/users/{userId}/notification-preferences.
 *
 * <p>Validates category and channel against the allowed enum vocabularies via regex
 * allow-lists rather than enum binding so FAIL_ON_UNKNOWN_PROPERTIES does the
 * structural check and Bean Validation does the vocabulary check.
 */
public record NotificationPreferenceUpdateRequest(
        @NotEmpty @Valid List<PreferenceItem> preferences
) {

    @JsonCreator
    public NotificationPreferenceUpdateRequest {}

    public record PreferenceItem(
            @NotNull
            @Pattern(
                regexp = "WO_ASSIGNED|WO_REASSIGNED|WO_STATUS_CHANGE|SLA_RISK|SLA_BREACH|" +
                         "APPOINTMENT_CHANGED|CERTIFICATION_EXPIRING|CERTIFICATION_EXPIRED|" +
                         "WO_DUPLICATE_LINKED|WO_REJECTED|CLOSURE_SURVEY",
                message = "category must be one of the recognised notification categories"
            )
            String category,

            @NotNull
            @Pattern(
                regexp = "EMAIL|SMS|PUSH|IN_APP",
                message = "channel must be one of: EMAIL, SMS, PUSH, IN_APP"
            )
            String channel,

            @NotNull Boolean enabled
    ) {}
}
