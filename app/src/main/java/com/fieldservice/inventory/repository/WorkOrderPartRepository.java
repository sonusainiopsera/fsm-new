package com.fieldservice.inventory.repository;

import com.fieldservice.inventory.domain.WorkOrderPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkOrderPartRepository extends JpaRepository<WorkOrderPart, UUID> {
    List<WorkOrderPart> findByWorkOrderIdAndMovementType(UUID workOrderId, String movementType);
}
