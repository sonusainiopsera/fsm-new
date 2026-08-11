package com.fieldservice.inventory.application;

/**
 * Thrown for invalid stock movement requests: zero/negative quantity, same-location
 * transfer, deactivated part consumption, or a technician without a configured vehicle
 * stock location.
 *
 * <p>Maps to HTTP 400 with code {@code INVALID_STOCK_MOVEMENT}.
 */
public class InvalidStockMovementException extends RuntimeException {

    private final String field;

    public InvalidStockMovementException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() { return field; }
}
