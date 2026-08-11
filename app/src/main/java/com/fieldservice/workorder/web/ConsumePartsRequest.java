package com.fieldservice.workorder.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{id}/parts.
 *
 * <p>See ADR-0009: insufficient stock returns 422 INSUFFICIENT_STOCK with per-line
 * detail, not 409.
 */
public record ConsumePartsRequest(
        @NotEmpty @Size(max = 50) @Valid List<LineItem> lines,
        UUID stockLocationId) {

    public record LineItem(
            UUID partId,
            @Positive int quantity,
            @Size(max = 100) String reasonCode) {}
}
