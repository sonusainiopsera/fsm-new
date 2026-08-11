package com.fieldservice.domain.inventory;

/**
 * @deprecated Replaced by the {@code movement_type} CHECK constraint in {@code stock_ledger}.
 * Valid values: CONSUMPTION, RETURN, REPLENISHMENT, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN.
 */
@Deprecated(forRemoval = true)
enum StockMovementType {
    CONSUMPTION, RETURN, REPLENISHMENT, ADJUSTMENT
}
