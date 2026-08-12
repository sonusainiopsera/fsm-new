package com.fieldservice.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link WorkOrderRequiredPart} entries.
 *
 * <p>Access is controlled at the service layer (callers enforce work-order scope
 * before loading required parts). Not a ScopedRepository — same pattern as
 * {@link WorkOrderPartRepository}.
 */
public interface WorkOrderRequiredPartRepository extends JpaRepository<WorkOrderRequiredPart, UUID> {

    List<WorkOrderRequiredPart> findByWorkOrderId(UUID workOrderId);
}
