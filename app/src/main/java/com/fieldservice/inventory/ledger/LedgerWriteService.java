package com.fieldservice.inventory.ledger;

import com.fieldservice.inventory.domain.StockLedger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Public facade for writing to the append-only stock ledger.
 *
 * <p>All writes use {@link Propagation#MANDATORY} so they are always part of the
 * caller's existing transaction — per BR-17, the ledger insert and the balance change
 * must commit atomically.
 *
 * <p>The Micrometer counter {@code inventory_ledger_entries_written_total} is incremented
 * on every persisted entry.
 */
@Service
public class LedgerWriteService {

    private final StockLedgerRepository ledgerRepository;
    private final Counter               entriesWrittenCounter;

    public LedgerWriteService(StockLedgerRepository ledgerRepository,
                               MeterRegistry meterRegistry) {
        this.ledgerRepository = ledgerRepository;
        this.entriesWrittenCounter = Counter.builder("inventory_ledger_entries_written_total")
                .description("Total stock ledger entries written")
                .register(meterRegistry);
    }

    /**
     * Records a single movement entry. Must be called within an active transaction.
     *
     * @param partId            the part being moved
     * @param fromLocationId    source location (always required)
     * @param toLocationId      destination (null except for TRANSFER movements)
     * @param deltaQuantity     signed delta (negative = consumption/transfer-out)
     * @param resultingQuantity quantity on hand at fromLocationId after the movement
     * @param movementType      CONSUMPTION | RETURN | TRANSFER | ADJUSTMENT | RECEIPT
     * @param reasonCode        optional reason code
     * @param workOrderId       nullable — null for adjustments and receipts
     * @param actorUserId       the user performing the movement
     * @param correlationId     groups entries belonging to the same batch or transfer pair
     * @param idempotencyKey    optional caller key for replay safety
     * @param occurredAt        when the movement occurred (from injected Clock)
     * @return the persisted entry
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockLedger record(UUID partId,
                               UUID fromLocationId,
                               UUID toLocationId,
                               int deltaQuantity,
                               Integer resultingQuantity,
                               String movementType,
                               String reasonCode,
                               UUID workOrderId,
                               UUID actorUserId,
                               UUID correlationId,
                               String idempotencyKey,
                               Instant occurredAt) {
        StockLedger entry = new StockLedger(
                partId, fromLocationId, toLocationId,
                deltaQuantity, resultingQuantity, movementType,
                reasonCode, workOrderId, actorUserId,
                correlationId, idempotencyKey, occurredAt);
        StockLedger saved = ledgerRepository.save(entry);
        entriesWrittenCounter.increment();
        return saved;
    }
}
