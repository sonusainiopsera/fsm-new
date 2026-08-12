package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for SLA breach records.
 *
 * <p>No delete method is declared — breach records are append-and-revise only.
 */
interface SlaBreachRepository extends JpaRepository<SlaBreachEntity, UUID> {

    boolean existsByWorkOrderIdAndBreachType(UUID workOrderId, String breachType);

    Optional<SlaBreachEntity> findByWorkOrderIdAndBreachType(UUID workOrderId, String breachType);

    List<SlaBreachEntity> findByWorkOrderIdAndFinalOverrunMinutesIsNull(UUID workOrderId);

    @Query("""
            SELECT b FROM SlaBreachEntity b
            WHERE (:breachType IS NULL OR b.breachType = :breachType)
              AND (:from IS NULL OR b.detectedAt >= :from)
              AND (:to   IS NULL OR b.detectedAt <= :to)
            ORDER BY b.detectedAt ASC, b.id ASC
            """)
    List<SlaBreachEntity> findFiltered(
            @Param("breachType") String breachType,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            SELECT COUNT(b) FROM SlaBreachEntity b
            WHERE (:breachType IS NULL OR b.breachType = :breachType)
              AND (:from IS NULL OR b.detectedAt >= :from)
              AND (:to   IS NULL OR b.detectedAt <= :to)
            """)
    long countFiltered(
            @Param("breachType") String breachType,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
