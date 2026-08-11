package com.fieldservice.app.arch.fixture;

import com.fieldservice.workorder.domain.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Deliberately non-compliant test fixture.
 *
 * <p>This interface violates the scoped-repository rule: it extends {@link JpaRepository}
 * for a {@link com.fieldservice.platform.persistence.ScopedEntity} type ({@link WorkOrder})
 * without also extending {@link com.fieldservice.platform.persistence.ScopedRepository}.
 *
 * <p>It exists solely to prove that {@code ScopedRepositoryFitnessTest} still fires when
 * the rule is violated. It must never be used in production code or registered as a Spring
 * Data bean.
 */
interface NonCompliantWorkOrderRepository extends JpaRepository<WorkOrder, UUID> {
    // Intentionally bypasses ScopedRepository — for rule-firing proof only.
}
