package com.fieldservice.architecture.fixture;

import com.fieldservice.domain.workorder.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This interface exists solely to verify that the ArchUnit scoped-repository rule
 * in {@link com.fieldservice.architecture.ScopedRepositoryArchTest} fires when a repository
 * for a {@link com.fieldservice.platform.persistence.ScopedEntity} (WorkOrder) bypasses
 * {@link com.fieldservice.platform.persistence.ScopedRepository}.
 *
 * <p>This interface must never be used in production code or wired as a Spring bean.
 */
public interface UnscopedWorkOrderRepository extends JpaRepository<WorkOrder, UUID> {
    // No methods — this interface exists only to trigger the ArchUnit violation rule.
}
