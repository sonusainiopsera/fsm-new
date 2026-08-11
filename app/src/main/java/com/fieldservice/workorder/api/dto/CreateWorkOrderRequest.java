package com.fieldservice.workorder.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for work order creation (POST /api/v1/work-orders).
 *
 * <p>Unknown JSON properties are rejected ({@code ignoreUnknown = false}) to prevent
 * mass-assignment of state, deadlines, assignee, or version fields (AC-5).
 *
 * <p>Client-supplied state, deadlines, assignee, version, and audit fields are not
 * declared here and are therefore rejected by Jackson before any binding occurs.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateWorkOrderRequest(

        @NotNull
        UUID customerId,

        @NotNull
        UUID siteId,

        UUID assetId,

        @NotBlank
        @Size(min = 10, max = 4000,
              message = "faultDescription must be between 10 and 4000 characters")
        String faultDescription,

        @NotBlank
        @Size(max = 500)
        String title,

        @NotBlank
        @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL",
                 message = "priority must be one of LOW, MEDIUM, HIGH, CRITICAL")
        String priority,

        List<@Pattern(regexp = "[A-Z0-9_\\-]+") String> requiredCertificationCodes,

        @Valid
        List<ExpectedPart> expectedParts
) {

    /** One expected part line on the work order intake form. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExpectedPart(
            @NotNull UUID partId,
            @Min(1) int quantity
    ) {}
}
