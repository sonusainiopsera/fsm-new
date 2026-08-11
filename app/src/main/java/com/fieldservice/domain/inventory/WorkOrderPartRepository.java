package com.fieldservice.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link WorkOrderPart} consumption ledger records.
 *
 * <p>Intentionally extends {@link JpaRepository} directly (not {@link com.fieldservice.platform.persistence.ScopedRepository})
 * because {@link WorkOrderPart} is not a row-scoped entity — access is controlled at the
 * service layer, which already enforces work-order ownership via {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 * This repository is on the non-scoped allow-list in {@code ScopedRepositoryArchTest}.
 */
public interface WorkOrderPartRepository extends JpaRepository<WorkOrderPart, UUID> {

    List<WorkOrderPart> findByWorkOrderId(UUID workOrderId);
}
