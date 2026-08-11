package com.fieldservice.domain.inventory;

/**
 * Type of stock movement performed by a technician.
 */
public enum StockMovementType {
    /** Parts consumed on a work order. */
    CONSUMPTION,
    /** Parts returned to warehouse. */
    RETURN,
    /** Parts received from warehouse replenishment. */
    REPLENISHMENT,
    /** Manual adjustment (admin only). */
    ADJUSTMENT
}
