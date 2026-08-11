package com.fieldservice.analytics.internal.quality;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ClosureProjectionRepository extends JpaRepository<ClosureProjectionEntity, UUID> {

    Optional<ClosureProjectionEntity> findByWorkOrderId(UUID workOrderId);

    /**
     * Finds classifiable earlier closures with the same asset and fault that could form
     * a repeat-visit relationship with a later closure.
     * The caller applies the 30-day window filter using {@code windowStart}.
     */
    @Query("""
            SELECT e FROM ClosureProjectionEntity e
            WHERE e.assetId   = :assetId
              AND e.faultKey  = :faultKey
              AND e.closedAt >= :windowStart
              AND e.closedAt  < :laterClosedAt
            ORDER BY e.closedAt DESC
            """)
    List<ClosureProjectionEntity> findPriorClosures(
            @Param("assetId")        UUID    assetId,
            @Param("faultKey")       String  faultKey,
            @Param("windowStart")    Instant windowStart,
            @Param("laterClosedAt")  Instant laterClosedAt
    );

    /**
     * Returns rows eligible for promotion from PROVISIONAL to MATURED.
     * Called by {@link MaturationSweepJob}.
     */
    @Query("SELECT e FROM ClosureProjectionEntity e WHERE e.maturity = 'PROVISIONAL' AND e.maturedAt <= :now")
    List<ClosureProjectionEntity> findEligibleForPromotion(@Param("now") Instant now);

    /**
     * Bulk-promotes all eligible rows. Idempotent — rows already MATURED are unaffected
     * by the WHERE clause.
     */
    @Modifying
    @Query("UPDATE ClosureProjectionEntity e SET e.maturity = 'MATURED' WHERE e.maturity = 'PROVISIONAL' AND e.maturedAt <= :now")
    int promoteEligible(@Param("now") Instant now);
}
