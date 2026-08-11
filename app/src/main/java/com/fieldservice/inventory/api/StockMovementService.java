package com.fieldservice.inventory.api;

import com.fieldservice.inventory.application.ConsumePartsCommand;
import com.fieldservice.inventory.application.ReturnPartsCommand;

/**
 * Public API for all stock write operations.
 *
 * <p>This is the exclusive writer of the {@code stock_balance} and related tables.
 * No other component may call stock repositories directly for mutations
 * (enforced by ArchUnit).
 *
 * <p>Each operation executes in a single @Transactional boundary that atomically:
 * conditionally decrements (or increments) the balance, persists a
 * {@code work_order_part} consumption record, writes a {@code stock_ledger} entry,
 * and emits an outbox event.
 *
 * <p>See ADR-0009 for the HTTP 422 vs 409 status-code resolution.
 */
public interface StockMovementService {

    /**
     * Logs parts consumption against a work order from a specific stock location.
     * All lines succeed or all lines are rejected (no partial consumption).
     *
     * @throws com.fieldservice.inventory.application.InsufficientStockException  when
     *         any line lacks sufficient stock — HTTP 422, balance untouched
     * @throws com.fieldservice.inventory.application.InvalidStockMovementException when
     *         the command is structurally invalid — HTTP 400
     */
    StockMovementResult consumeParts(ConsumePartsCommand command);

    /**
     * Returns unused parts from a work order back to the stock location.
     *
     * @throws com.fieldservice.inventory.application.InvalidStockMovementException when
     *         the command is invalid — HTTP 400
     */
    StockMovementResult returnParts(ReturnPartsCommand command);
}
