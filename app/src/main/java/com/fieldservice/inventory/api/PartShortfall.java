package com.fieldservice.inventory.api;

import java.util.UUID;

/**
 * Per-part shortfall detail included in an availability result when the
 * candidate does not have sufficient stock for one required part.
 *
 * @param partId     identifier of the part with insufficient stock
 * @param partNumber human-readable part number for display
 * @param requested  quantity required by the work order
 * @param available  quantity on hand at the candidate's location (0 if absent)
 */
public record PartShortfall(UUID partId, String partNumber, int requested, int available) {}
