package com.fieldservice.inventory.api;

import java.util.UUID;

/**
 * Per-part shortfall detail within a candidate availability assessment.
 *
 * @param partId     part identifier
 * @param partNumber human-readable part number
 * @param requested  quantity required by the work order
 * @param available  total quantity available (vehicle + any reachable warehouse)
 */
public record PartShortfall(UUID partId, String partNumber, int requested, int available) {
}
