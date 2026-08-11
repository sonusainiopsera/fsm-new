package com.fieldservice.inventory.ledger;

import java.util.UUID;

/** JPQL projection for the reconciliation GROUP BY query. */
public interface LedgerSumProjection {
    UUID getPartId();
    UUID getLocationId();
    Long getTotalDelta();
}
