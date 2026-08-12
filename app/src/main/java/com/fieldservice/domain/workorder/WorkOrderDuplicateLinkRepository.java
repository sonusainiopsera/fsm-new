package com.fieldservice.domain.workorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkOrderDuplicateLinkRepository extends JpaRepository<WorkOrderDuplicateLink, UUID> {

    Optional<WorkOrderDuplicateLink> findBySourceWorkOrderId(UUID sourceWorkOrderId);

    List<WorkOrderDuplicateLink> findByTargetWorkOrderId(UUID targetWorkOrderId);

    boolean existsBySourceWorkOrderId(UUID sourceWorkOrderId);
}
