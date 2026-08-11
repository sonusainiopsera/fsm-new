package com.fieldservice.inventory.application;

import java.util.List;
import java.util.UUID;

/**
 * Command record for returning unused parts from a vehicle stock location
 * back against a work order.
 */
public record ReturnPartsCommand(
        UUID workOrderId,
        UUID stockLocationId,
        List<LineItem> lines,
        UUID actorUserId,
        String idempotencyKey) {

    public record LineItem(UUID partId, int quantity, String reasonCode) {}

    public ReturnPartsCommand {
        lines = lines.stream()
                .sorted(java.util.Comparator.comparing(l -> l.partId().toString()))
                .toList();
    }
}
