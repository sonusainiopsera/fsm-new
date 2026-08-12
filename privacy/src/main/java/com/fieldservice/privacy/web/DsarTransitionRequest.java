package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.DsarEvent;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for POST /api/v1/privacy/dsar-requests/{id}/transitions.
 */
public record DsarTransitionRequest(

        @NotNull(message = "event is required")
        DsarEvent event,

        /** Required when event is RECORD_VERIFICATION or VERIFY_DIRECT. */
        String verificationMethod,

        String note,

        @NotNull(message = "version is required for optimistic locking")
        Integer version
) {}
