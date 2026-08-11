package com.fieldservice.privacy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/privacy/subjects/{subjectType}/{subjectId}/erasure}.
 *
 * @param dsarRequestId authorising DSAR request (must be in state VERIFIED or later)
 * @param confirmation  must be the exact string {@code "CONFIRM_ERASURE"} to prevent accidental erasure
 * @param note          optional note recorded on the tombstone
 */
public record InitiateErasureRequest(
        @NotNull UUID dsarRequestId,
        @NotBlank String confirmation,
        @Size(max = 1000) String note
) {}
