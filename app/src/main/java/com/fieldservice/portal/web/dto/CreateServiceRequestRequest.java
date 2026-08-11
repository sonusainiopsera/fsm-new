package com.fieldservice.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Portal service-request submission DTO.
 *
 * <p>Unknown JSON properties are rejected ({@code ignoreUnknown = false}) to prevent
 * mass-assignment of state, priority, deadlines, or other internal fields (AC-5).
 *
 * <p>Priority is NOT customer-selectable — portal submissions receive a configured default
 * priority. The field is absent from this DTO by design; callers who supply it receive 400.
 *
 * <p>faultDescription length is capped at 2000 characters (minimum 4). Free-text input
 * is stored via parameterised JDBC — never interpolated into SQL or logged above DEBUG.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateServiceRequestRequest(

        @NotNull(message = "siteId is required")
        UUID siteId,

        UUID assetId,

        @NotBlank(message = "faultDescription is required")
        @Size(min = 4, max = 2000,
              message = "faultDescription must be between 4 and 2000 characters")
        String faultDescription,

        @NotBlank(message = "contactPreference is required")
        @Pattern(regexp = "EMAIL|PHONE",
                 message = "contactPreference must be EMAIL or PHONE")
        String contactPreference,

        @Valid
        PreferredWindow preferredWindow

) {

    /**
     * Optional time-window preference for the technician visit.
     * Both instants must be present when the object is provided.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PreferredWindow(
            @NotNull(message = "preferredWindow.fromAt is required")
            Instant fromAt,

            @NotNull(message = "preferredWindow.toAt is required")
            Instant toAt
    ) {}
}
