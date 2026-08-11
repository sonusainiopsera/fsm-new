package com.fieldservice.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Read/write access to parts consumption records for work orders.
 *
 * <p>Not a scoped repository: access is through the inventory module's public API surface
 * ({@link com.fieldservice.inventory.api.WorkOrderConsumptionQueryPort}), which enforces
 * authorisation at the service layer.
 */
public interface WorkOrderPartConsumptionRepository extends JpaRepository<WorkOrderPartConsumption, UUID> {

    boolean existsByWorkOrderIdAndReconciledFalse(UUID workOrderId);
}
