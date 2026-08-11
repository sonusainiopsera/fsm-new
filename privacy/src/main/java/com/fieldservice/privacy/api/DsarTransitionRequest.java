package com.fieldservice.privacy.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code POST /api/v1/privacy/dsar-requests/{id}/transitions}.
 *
 * <p>{@code verificationMethod} is required when the event is {@code VERIFY};
 * the guard enforces this server-side rather than through bean validation so
 * the error message includes the context.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DsarTransitionRequest(
        @NotBlank String event,
        @Nullable String verificationMethod,
        @Nullable String note
) {}
