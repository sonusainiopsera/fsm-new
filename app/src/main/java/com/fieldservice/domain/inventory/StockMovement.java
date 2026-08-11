package com.fieldservice.domain.inventory;

/**
 * @deprecated The {@code stock_movement} table was removed in V1 and replaced by
 * the {@link StockLedger} (append-only journal) and {@link StockBalance} (running total) tables.
 * This class is retained as a compile error marker — delete it and use {@link StockLedger} instead.
 */
@Deprecated(forRemoval = true)
final class StockMovement {
    private StockMovement() {}
}
