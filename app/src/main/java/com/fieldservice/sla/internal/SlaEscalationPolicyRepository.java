package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Package-private JPA repository for {@link SlaEscalationPolicy} entities.
 */
interface SlaEscalationPolicyRepository extends JpaRepository<SlaEscalationPolicy, UUID> {

    /**
     * Returns the most-recently-effective active policy for the given event type and priority.
     * Policies with a future {@code effective_from} are excluded.
     */
    @Query("""
            SELECT p FROM SlaEscalationPolicy p
            WHERE p.eventType = :eventType
              AND p.priority  = :priority
              AND p.active    = true
              AND p.effectiveFrom <= :now
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :now)
            ORDER BY p.effectiveFrom DESC
            """)
    Optional<SlaEscalationPolicy> findActivePolicy(
            @Param("eventType") String eventType,
            @Param("priority")  String priority,
            @Param("now")       Instant now);
}
