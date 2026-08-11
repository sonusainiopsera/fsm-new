package com.fieldservice.inventory.application;

import java.util.UUID;

/** Outbox event payload for PARTS_CONSUMED and PARTS_RETURNED events. */
public record PartsConsumedPayload(
        UUID   workOrderId,
        UUID   stockLocationId,
        int    lineCount,
        String movementType) {}
