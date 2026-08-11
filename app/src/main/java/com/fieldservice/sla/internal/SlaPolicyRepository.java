package com.fieldservice.sla.internal;

import com.fieldservice.domain.sla.SlaPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Package-private JPA repository for SLA policy entities.
 * Not accessible outside the sla module.
 */
interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    /**
     * Finds all active policy rows for a priority at a given instant, ordered by
     * effective_from descending (latest first). Callers take the first result.
     *
     * <p>A row qualifies if:
     * <ul>
     *   <li>priority matches</li>
     *   <li>effective_from <= at</li>
     *   <li>effective_to IS NULL OR effective_to > at</li>
     *   <li>active = true</li>
     * </ul>
     */
    @Query("""
            SELECT p FROM SlaPolicy p
            WHERE p.priority = :priority
              AND p.active = true
              AND p.effectiveFrom <= :at
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
            ORDER BY p.effectiveFrom DESC, p.id DESC
            """)
    List<SlaPolicy> findActiveForPriorityAt(
            @Param("priority") String priority,
            @Param("at") Instant at);

    /**
     * Returns all policies (active or expired) for display in the admin list.
     */
    @Query("""
            SELECT p FROM SlaPolicy p
            ORDER BY p.priority ASC, p.effectiveFrom DESC
            """)
    List<SlaPolicy> findAllOrderedByPriorityAndEffectiveFrom();

    /** Finds any policy by ID, for supersede-on-update operations. */
    Optional<SlaPolicy> findById(UUID id);
}
