package com.fieldservice.sla.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for PUT /api/v1/admin/sla-policies/{id} — in-place update with
 * optimistic locking via the {@code version} field.
 *
 * <p>Unlike the create request, {@code priority} and {@code effectiveFrom} are
 * not updatable: priority is the primary discriminant of the row, and effective-from
 * is a time-versioning anchor. Use the supersede operation to change those.
 *
 * <p>Cross-field validation: {@code resolutionMinutes >= responseMinutes}.
 */
@SlaPolicyValid
@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminSlaPolicyUpdateRequest(

        @NotNull @Positive
        Integer responseMinutes,

        @NotNull @Positive
        Integer resolutionMinutes,

        @NotNull
        @DecimalMin(value = "0.50", message = "atRiskFraction must be >= 0.50")
        @DecimalMax(value = "1.00", message = "atRiskFraction must be <= 1.00")
        BigDecimal atRiskFraction,

        Boolean ratified,

        @NotNull(message = "version is required for optimistic locking")
        Integer version
) {
    public boolean isRatified() {
        return Boolean.TRUE.equals(ratified);
    }
}
