package com.fieldservice.inventory.application;

import java.util.List;
import java.util.UUID;

/**
 * Command record for logging parts consumption against a work order.
 *
 * <p>Lines are sorted by partId before processing to produce deterministic
 * log ordering (correctness does not depend on ordering — no lock waiting).
 */
public record ConsumePartsCommand(
        UUID workOrderId,
        UUID stockLocationId,
        List<LineItem> lines,
        UUID actorUserId,
        String idempotencyKey) {

    public record LineItem(UUID partId, int quantity, String reasonCode) {}

    public ConsumePartsCommand {
        lines = lines.stream()
                .sorted(java.util.Comparator.comparing(l -> l.partId().toString()))
                .toList();
    }
}
