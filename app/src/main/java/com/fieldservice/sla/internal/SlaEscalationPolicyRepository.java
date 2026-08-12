package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
interface SlaEscalationPolicyRepository extends JpaRepository<SlaEscalationPolicy, UUID> {

    @Query("""
            SELECT p FROM SlaEscalationPolicy p
            WHERE p.active = TRUE
              AND p.effectiveTo IS NULL
              AND p.eventType = :eventType
              AND (p.priority = :priority OR p.priority = '*')
            ORDER BY CASE WHEN p.priority = :priority THEN 0 ELSE 1 END
            """)
    List<SlaEscalationPolicy> findActivePolicies(
            @Param("eventType") String eventType,
            @Param("priority")  String priority);
}
