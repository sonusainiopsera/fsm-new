package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Package-private JPA repository for {@link SlaRiskFlag} entities.
 */
interface SlaRiskFlagRepository extends JpaRepository<SlaRiskFlag, UUID> {

    /** Returns all open flags for a work order (cleared_at IS NULL). */
    List<SlaRiskFlag> findByWorkOrderIdAndClearedAtIsNull(UUID workOrderId);

    /** Returns a specific open flag by work order and type, if any. */
    Optional<SlaRiskFlag> findByWorkOrderIdAndFlagTypeAndClearedAtIsNull(UUID workOrderId, String flagType);

    /** Checks whether any open flag of any type exists for the given work order. */
    boolean existsByWorkOrderIdAndClearedAtIsNull(UUID workOrderId);

    /**
     * Counts open flags by trigger reason — used by Micrometer gauge to backfill
     * the {@code sla_risk_flags_total} counter after a restart.
     */
    @Query("SELECT f.triggerReason, COUNT(f) FROM SlaRiskFlag f WHERE f.clearedAt IS NULL GROUP BY f.triggerReason")
    List<Object[]> countOpenFlagsByTriggerReason();

    /**
     * Counts all flags raised since the epoch (for Micrometer counter initialization).
     */
    @Query("SELECT COUNT(f) FROM SlaRiskFlag f WHERE f.triggerReason = :reason")
    long countByTriggerReason(@Param("reason") String reason);

    /**
     * Bulk-closes all open flags for a terminal-state work order.
     * Used when a work order reaches COMPLETED, CLOSED, or CANCELLED mid-sweep.
     */
    @Modifying
    @Query("""
            UPDATE SlaRiskFlag f
            SET f.clearedAt = :now, f.clearReason = :reason
            WHERE f.workOrderId = :workOrderId AND f.clearedAt IS NULL
            """)
    int closeAllOpenFlags(@Param("workOrderId") UUID workOrderId,
                          @Param("reason") String reason,
                          @Param("now") java.time.Instant now);
}
