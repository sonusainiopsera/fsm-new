package com.fieldservice.inventory.api;

import java.util.UUID;

/**
 * Public interface for all stock movement operations.
 *
 * <p>This is the only component in the codebase with write access to stock tables.
 * Controllers and other modules must call this interface; they must never import
 * inventory domain repositories directly (enforced by {@code InventoryBoundaryTest}).
 *
 * <p>Each operation:
 * <ul>
 *   <li>Executes as a single {@code @Transactional} unit — balance change, consumption
 *       record, Envers revision, and outbox event all commit together or not at all.</li>
 *   <li>Carries {@code @PreAuthorize} on the implementation to enforce role restrictions.</li>
 *   <li>Is idempotent when replayed with the same {@code Idempotency-Key} via the platform
 *       filter — the filter handles replay at the HTTP layer.</li>
 * </ul>
 */
public interface StockMovementService {

    /**
     * Logs parts consumption from a vehicle stock location against a work order.
     *
     * <p>All lines are applied atomically; if any line lacks sufficient stock the entire
     * request is refused with {@link InsufficientStockException} and no balance is changed.
     *
     * <p>TECHNICIAN: may only consume from a location they own, against a work order they
     * are assigned to. DISPATCHER and ADMIN: unrestricted.
     *
     * @param command the consumption command with work order, location, and line items
     * @return result with per-line resulting balances and reconciliation status
     * @throws InsufficientStockException   if any line lacks sufficient stock (422)
     * @throws InvalidMovementException     if the command is semantically invalid (400)
     * @throws com.fieldservice.platform.exception.NotFoundException if work order or location not found
     * @throws com.fieldservice.platform.exception.ForbiddenException if access scope is violated
     */
    ConsumePartsResult consumeParts(ConsumePartsCommand command);

    /**
     * Returns unused parts from a work order back to a vehicle stock location.
     *
     * <p>The return unconditionally increments the balance — no negative-check is needed
     * because adding stock cannot violate BR-16.
     *
     * @param command the return command with work order, location, and line items
     * @return result with per-line resulting balances
     * @throws InvalidMovementException if the command is semantically invalid (400)
     */
    ConsumePartsResult returnParts(ReturnPartsCommand command);

    /**
     * Transfers stock between two locations.
     *
     * <p>Implemented as a decrement on the source + increment on the destination in one transaction.
     * DISPATCHER and ADMIN only.
     *
     * @param sourceLocationId      stock location to take parts from
     * @param destinationLocationId stock location to add parts to
     * @param partId                the part to transfer
     * @param quantity              positive quantity to transfer
     * @param actorUserId           the authenticated actor
     * @throws InsufficientStockException if the source balance is insufficient
     * @throws InvalidMovementException   if source and destination are the same
     */
    void transferStock(UUID sourceLocationId, UUID destinationLocationId,
                       UUID partId, int quantity, UUID actorUserId);

    /**
     * Adjusts a stock balance by a signed delta (inventory count correction).
     *
     * <p>DISPATCHER and ADMIN only. Positive delta adds stock; negative delta removes stock
     * subject to the non-negative CHECK constraint.
     *
     * @param locationId  the location to adjust
     * @param partId      the part to adjust
     * @param delta       signed quantity change (non-zero)
     * @param actorUserId the authenticated actor
     * @throws InsufficientStockException if a negative adjustment would drive the balance below zero
     * @throws InvalidMovementException   if delta is zero
     */
    void adjustStock(UUID locationId, UUID partId, int delta, UUID actorUserId);
}
