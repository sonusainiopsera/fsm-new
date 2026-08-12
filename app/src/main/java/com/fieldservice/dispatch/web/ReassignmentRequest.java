package com.fieldservice.dispatch.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{workOrderId}/reassignment.
 *
 * <p>{@code reassignmentReason} is mandatory and must be a valid
 * {@link com.fieldservice.dispatch.internal.ReassignmentReason} name.
 * Unknown JSON properties are rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReassignmentRequest(

        @NotNull(message = "technicianId must not be null")
        UUID technicianId,

        @NotNull(message = "reassignmentReason must not be null")
        String reassignmentReason,

        @Size(max = 2000, message = "reasonNotes must be 2000 characters or fewer")
        String reasonNotes,

        UUID recommendationSnapshotId,

        @Size(max = 1000, message = "overrideReason must be 1000 characters or fewer")
        String overrideReason,

        @Size(max = 2000, message = "appointmentImpactAcknowledgement must be 2000 characters or fewer")
        String appointmentImpactAcknowledgement
) {}
