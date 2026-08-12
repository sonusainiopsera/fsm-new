package com.fieldservice.aiaudit.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Package-private JPA repository for {@link AiInteraction}.
 *
 * <p>No update or bulk-delete method is exposed — records are append-only by construction.
 * The only deletion path is the scheduled purge job via {@link #deleteExpiredBatch}.
 */
interface AiInteractionRepository extends JpaRepository<AiInteraction, UUID> {

    @Query("SELECT COUNT(a) FROM AiInteraction a WHERE a.outcome = :outcome AND a.createdAt >= :from AND a.createdAt < :to")
    long countByOutcomeAndWindow(@Param("outcome") String outcome,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    @Query("SELECT COUNT(a) FROM AiInteraction a WHERE a.createdAt >= :from AND a.createdAt < :to")
    long countByWindow(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms)
            FROM ai_interaction
            WHERE created_at >= :from AND created_at < :to AND latency_ms IS NOT NULL
            """, nativeQuery = true)
    Long findP95LatencyMs(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT COALESCE(AVG(estimated_cost), 0)
            FROM ai_interaction
            WHERE created_at >= :from AND created_at < :to
            """, nativeQuery = true)
    java.math.BigDecimal findAvgEstimatedCost(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT outcome, COUNT(*) AS cnt
            FROM ai_interaction
            WHERE created_at >= :from AND created_at < :to
            GROUP BY outcome
            """, nativeQuery = true)
    java.util.List<Object[]> findOutcomeDistribution(@Param("from") Instant from, @Param("to") Instant to);

    @Modifying
    @Query(value = """
            DELETE FROM ai_interaction
            WHERE id IN (
                SELECT id FROM ai_interaction
                WHERE retain_until < :now
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
