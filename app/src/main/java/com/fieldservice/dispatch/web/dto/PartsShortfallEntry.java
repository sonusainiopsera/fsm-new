package com.fieldservice.dispatch.web.dto;

import java.util.UUID;

/**
 * Per-part shortfall detail in the parts availability response.
 *
 * @param partId     identifier of the part with insufficient stock
 * @param partNumber human-readable part number for display
 * @param requested  quantity required by the work order
 * @param onHand     quantity on hand at this location (0 if absent)
 * @param shortfall  quantity still needed ({@code requested - onHand}, always >= 0)
 */
public record PartsShortfallEntry(
        UUID partId,
        String partNumber,
        int requested,
        int onHand,
        int shortfall
) {}
