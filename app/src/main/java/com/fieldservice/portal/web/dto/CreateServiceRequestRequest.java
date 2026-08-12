package com.fieldservice.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Request body for POST /api/v1/portal/service-requests.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} enforces the allow-list:
 * any unknown JSON property (e.g. {@code priority}, {@code state}) returns 400
 * rather than being silently discarded — prevents mass-assignment attacks.
 *
 * <p>Priority is NOT customer-selectable; portal submissions receive the configured
 * portal default priority enforced in {@link com.fieldservice.workorder.application.WorkOrderCreationService}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateServiceRequestRequest(

        @NotNull
        UUID siteId,

        /** Optional — null accepted when the fault is not tied to a specific asset. */
        UUID assetId,

        @NotBlank
        @Size(min = 4, max = 2000)
        String faultDescription,

        @NotNull
        ContactPreference contactPreference,

        @Valid
        PreferredWindow preferredWindow

) {

    /** Preferred contact channel for this service request. */
    public enum ContactPreference {
        EMAIL, PHONE
    }

    /** Optional preferred scheduling window for the visit. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PreferredWindow(
            @NotNull Instant fromAt,
            @NotNull Instant toAt
    ) {}
}
