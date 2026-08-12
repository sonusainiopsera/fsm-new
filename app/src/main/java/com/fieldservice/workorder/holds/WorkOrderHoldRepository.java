package com.fieldservice.workorder.holds;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkOrderHoldRepository extends JpaRepository<WorkOrderHold, UUID> {

    Optional<WorkOrderHold> findByWorkOrderIdAndEndedAtIsNull(UUID workOrderId);

    List<WorkOrderHold> findByWorkOrderId(UUID workOrderId);
}
