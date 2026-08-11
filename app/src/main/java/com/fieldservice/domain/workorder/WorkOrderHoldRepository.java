package com.fieldservice.domain.workorder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Non-scoped repository for {@link WorkOrderHold}.
 *
 * <p>Not a {@link com.fieldservice.platform.persistence.ScopedRepository} because hold
 * records are accessed inside an already-scoped work order transaction; the scope predicate
 * is enforced on the work order aggregate before any hold operation runs.
 */
public interface WorkOrderHoldRepository extends JpaRepository<WorkOrderHold, UUID> {

    Optional<WorkOrderHold> findByWorkOrderIdAndEndedAtIsNull(UUID workOrderId);
}
