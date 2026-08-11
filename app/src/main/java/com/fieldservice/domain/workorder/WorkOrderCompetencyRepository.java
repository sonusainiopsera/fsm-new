package com.fieldservice.domain.workorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Read/write access to required competency records for work orders.
 *
 * <p>Not a scoped repository: competency requirements are accessed in the context of an
 * already-scoped work order transition; scope enforcement on the work order is sufficient.
 */
public interface WorkOrderCompetencyRepository extends JpaRepository<WorkOrderCompetency, UUID> {

    List<WorkOrderCompetency> findByWorkOrderId(UUID workOrderId);
}
