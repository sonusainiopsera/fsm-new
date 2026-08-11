package com.fieldservice.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when a consumption request would drive a stock balance below zero.
 *
 * <p>Maps to HTTP 422 with code {@code INSUFFICIENT_STOCK} and per-line field errors
 * showing requested vs available quantities.
 */
public class InsufficientStockException extends RuntimeException {

    /** Per-line detail for multi-line refusals. */
    public record LineDetail(String field, UUID partId, UUID locationId, int requested, int available) {
        public String message() {
            return "requested " + requested + ", available " + available;
        }
    }

    private final List<LineDetail> lines;

    public InsufficientStockException(List<LineDetail> lines) {
        super("Insufficient stock for one or more requested lines");
        this.lines = List.copyOf(lines);
    }

    public List<LineDetail> getLines() { return lines; }
}
