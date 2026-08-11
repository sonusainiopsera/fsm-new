package com.fieldservice.inventory.api;

/**
 * Thrown when a stock movement request is semantically invalid (400).
 * Examples: zero/negative quantity, same source and destination for transfer.
 */
public class InvalidMovementException extends RuntimeException {

    public InvalidMovementException(String message) {
        super(message);
    }
}
