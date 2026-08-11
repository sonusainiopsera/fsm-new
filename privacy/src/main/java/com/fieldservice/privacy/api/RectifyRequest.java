package com.fieldservice.privacy.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/privacy/subjects/{subjectType}/{subjectId}/rectifications}.
 *
 * @param dsarRequestId authorising DSAR request (must be in state VERIFIED or later)
 * @param corrections   field corrections to apply (at least one required)
 * @param note          optional note recorded on the DSAR request
 */
public record RectifyRequest(
        @NotNull UUID dsarRequestId,
        @NotEmpty @Valid List<FieldCorrection> corrections,
        @Size(max = 1000) String note
) {

    /**
     * A single field correction.
     *
     * @param entityName simple class name of the entity to correct (e.g. {@code "Customer"})
     * @param fieldName  field to correct (e.g. {@code "primaryContactEmail"})
     * @param newValue   the replacement value
     */
    public record FieldCorrection(
            @NotBlank String entityName,
            @NotBlank String fieldName,
            @NotNull @Size(max = 500) String newValue
    ) {}
}
