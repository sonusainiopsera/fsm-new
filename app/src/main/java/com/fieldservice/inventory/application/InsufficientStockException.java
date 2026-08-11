package com.fieldservice.inventory.application;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when a consumption request cannot be satisfied because one or more
 * balance rows have insufficient quantity.
 *
 * <p>Carries per-line detail so the HTTP handler can build field-level errors.
 * Maps to HTTP 422 with code {@code INSUFFICIENT_STOCK} per ADR-0009.
 */
public class InsufficientStockException extends RuntimeException {

    public record ShortfallLine(UUID partId, UUID locationId, int requested, int available) {}

    private final List<ShortfallLine> shortfalls;

    public InsufficientStockException(List<ShortfallLine> shortfalls) {
        super("Insufficient stock for " + shortfalls.size() + " line(s)");
        this.shortfalls = List.copyOf(shortfalls);
    }

    public List<ShortfallLine> getShortfalls() { return shortfalls; }
}
