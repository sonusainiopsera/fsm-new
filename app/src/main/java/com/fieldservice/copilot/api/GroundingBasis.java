package com.fieldservice.copilot.api;

import java.util.List;
import java.util.UUID;

/**
 * Machine-readable record of the asset and work orders that grounded a copilot response.
 * Attached to every answer so downstream layers can display attribution without re-querying.
 */
public record GroundingBasis(
        UUID assetId,
        UUID workOrderId,
        List<UUID> contributingPriorWorkOrderIds) {
}
