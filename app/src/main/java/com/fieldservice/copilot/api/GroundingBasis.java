package com.fieldservice.copilot.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Machine-readable basis identifying exactly which records grounded an AI prompt.
 *
 * <p>Attached to every outbound copilot request so downstream layers (WO-082) can
 * display attribution without re-querying. Contains only opaque identifiers — no PII.
 *
 * @param assetId                  the UUID of the asset the prompt is grounded in (may be {@code null} if no asset)
 * @param assetName                opaque display label (already redacted — safe to surface in UI)
 * @param contributingWorkOrderIds UUIDs of all prior work orders that contributed to the context, newest first
 */
public record GroundingBasis(
        UUID assetId,
        String assetName,
        List<UUID> contributingWorkOrderIds
) {
    public GroundingBasis {
        Objects.requireNonNull(contributingWorkOrderIds, "contributingWorkOrderIds");
        contributingWorkOrderIds = List.copyOf(contributingWorkOrderIds);
    }
}
