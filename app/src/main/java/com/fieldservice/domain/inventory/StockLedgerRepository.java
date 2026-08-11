package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StockLedgerRepository extends ScopedRepository<StockLedger, UUID> {

    List<StockLedger> findByWorkOrderId(UUID workOrderId);

    /** Sums quantity_delta per (part_id, location_id) for reconciliation. */
    @Query("""
            SELECT e.partId AS partId, e.locationId AS locationId, SUM(e.quantityDelta) AS totalDelta
            FROM StockLedger e
            GROUP BY e.partId, e.locationId
            """)
    List<LedgerSum> sumDeltaByPartAndLocation();

    /** Fetches entries for a work order after occurred_at for completeness metric. */
    @Query("""
            SELECT COUNT(e) FROM StockLedger e
            WHERE e.workOrderId = :workOrderId
              AND e.occurredAt >= :after
            """)
    long countEntriesForWorkOrderAfter(@Param("workOrderId") UUID workOrderId,
                                       @Param("after") Instant after);

    interface LedgerSum {
        UUID getPartId();
        UUID getLocationId();
        long getTotalDelta();
    }
}
