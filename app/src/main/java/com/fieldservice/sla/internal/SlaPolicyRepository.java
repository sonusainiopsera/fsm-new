package com.fieldservice.sla.internal;

import com.fieldservice.sla.domain.SlaPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    /**
     * Resolves the active policy for a priority at the given instant.
     * Time-versioned: picks the row whose effective_from is on or before {@code at}
     * and whose effective_to is null or after {@code at}. Tie-broken by effective_from DESC then id.
     */
    @Query("""
            SELECT p FROM SlaPolicy p
            WHERE p.priority = :priority
              AND p.effectiveFrom <= :at
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
              AND p.active = true
            ORDER BY p.effectiveFrom DESC, p.id DESC
            LIMIT 1
            """)
    Optional<SlaPolicy> findActiveByPriorityAt(@Param("priority") String priority,
                                               @Param("at") Instant at);

    @Query("SELECT p FROM SlaPolicy p WHERE p.priority = :priority AND p.active = true ORDER BY p.effectiveFrom DESC")
    List<SlaPolicy> findActiveByPriority(@Param("priority") String priority);

    List<SlaPolicy> findAllByOrderByPriorityAscEffectiveFromDesc();
}
