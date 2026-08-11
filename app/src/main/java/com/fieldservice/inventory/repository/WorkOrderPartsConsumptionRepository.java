package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.WorkOrderPartsConsumption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkOrderPartsConsumptionRepository extends JpaRepository<WorkOrderPartsConsumption, UUID> {
    boolean existsByWorkOrderId(UUID workOrderId);
    boolean existsByWorkOrderIdAndReconciledFalse(UUID workOrderId);
}
