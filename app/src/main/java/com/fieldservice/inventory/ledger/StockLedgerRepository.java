package com.fieldservice.inventory.ledger;

import com.fieldservice.inventory.domain.StockLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Insert-only repository for the stock ledger.
 *
 * <p>Design contract: only {@link #save}, {@link #saveAll}, and the declared query
 * methods are intended for use. The inherited {@code delete*} and {@code deleteAll}
 * methods must never be called — this is enforced at build time by an ArchUnit rule
 * ({@code StockLedgerAppendOnlyTest}) and at runtime by the database role which holds
 * only INSERT + SELECT on the stock_ledger table.
 *
 * <p>Package-private: consumers outside this package must use
 * {@link com.fieldservice.inventory.ledger.LedgerWriteService} to write entries.
 */
@Repository
public interface StockLedgerRepository
        extends JpaRepository<StockLedger, UUID>, JpaSpecificationExecutor<StockLedger> {

    Page<StockLedger> findAll(Specification<StockLedger> spec, Pageable pageable);

    List<StockLedger> findByWorkOrderId(UUID workOrderId);

    List<StockLedger> findByCorrelationId(UUID correlationId);

    /** Reconciliation query: sum delta_quantity per (part, location) pair. */
    @Query("""
            SELECT sl.partId AS partId,
                   sl.fromLocationId AS locationId,
                   SUM(sl.deltaQuantity) AS totalDelta
            FROM StockLedger sl
            GROUP BY sl.partId, sl.fromLocationId
            """)
    List<LedgerSumProjection> sumDeltasByPartAndLocation();

    /** Count entries written in the given time window — used for ledger_entries_written_total gauge. */
    @Query("SELECT COUNT(sl) FROM StockLedger sl WHERE sl.occurredAt >= :from AND sl.occurredAt < :to")
    long countInWindow(@Param("from") Instant from, @Param("to") Instant to);

    /** Work orders with at least one ledger entry in the trailing window. */
    @Query("""
            SELECT DISTINCT sl.workOrderId FROM StockLedger sl
            WHERE sl.workOrderId IS NOT NULL
              AND sl.occurredAt >= :since
              AND sl.movementType IN ('CONSUMPTION', 'RETURN')
            """)
    List<UUID> workOrderIdsWithMovementsSince(@Param("since") Instant since);
}
