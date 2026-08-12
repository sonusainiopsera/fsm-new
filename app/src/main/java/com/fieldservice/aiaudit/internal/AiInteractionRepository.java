package com.fieldservice.aiaudit.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Package-private repository for {@link AiInteraction}.
 *
 * <p>Append-only invariant: no {@code delete}, {@code deleteAll}, or update methods
 * are exposed. The only mutation allowed is physical deletion of expired rows by the
 * purge job (via the bounded-batch query below).
 */
interface AiInteractionRepository extends JpaRepository<AiInteraction, UUID> {

    // ── Reads used by query service ───────────────────────────────────────────

    List<AiInteraction> findByActorUserId(UUID actorUserId);

    @Query("""
            SELECT a FROM AiInteraction a
            WHERE a.createdAt >= :from AND a.createdAt < :to
            ORDER BY a.createdAt DESC
            """)
    List<AiInteraction> findByWindow(@Param("from") Instant from, @Param("to") Instant to);

    // ── Purge job: bounded batch physical deletion ────────────────────────────

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

    // ── Metrics queries ───────────────────────────────────────────────────────

    @Query("""
            SELECT COUNT(a) FROM AiInteraction a
            WHERE a.createdAt >= :from AND a.createdAt < :to
            """)
    long countByWindow(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms)
            FROM ai_interaction
            WHERE created_at >= :from AND created_at < :to
              AND latency_ms IS NOT NULL
            """, nativeQuery = true)
    Double computeP95LatencyMs(@Param("from") Instant from, @Param("to") Instant to);
}
