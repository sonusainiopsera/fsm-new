package com.fieldservice.portal.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * CSAT survey response submission DTO (WO-173).
 *
 * <p>Unknown JSON properties are rejected to prevent mass-assignment.
 * Comment is nullable — customers may skip free-text feedback.
 * Comment content is stored encrypted at rest and never logged.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SubmitResponseRequest(

        @NotNull(message = "score is required")
        @Min(value = 1, message = "score must be between 1 and 5")
        @Max(value = 5, message = "score must be between 1 and 5")
        Short score,

        @Min(value = 0, message = "npsScore must be between 0 and 10")
        @Max(value = 10, message = "npsScore must be between 0 and 10")
        Short npsScore,

        @Size(max = 1000, message = "comment must not exceed 1000 characters")
        String comment

) {}
