package com.fieldservice.domain.workorder;

import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

/**
 * Repository for {@link WorkOrder} entities.
 *
 * <p><strong>Access policy:</strong> All reads must route through
 * {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 * Direct calls to {@code findById} or {@code findAll} bypass row-scope enforcement
 * and are forbidden in service-layer code (enforced by ArchUnit).
 */
public interface WorkOrderRepository extends ScopedRepository<WorkOrder, UUID> {

    /**
     * Internal purpose check used by position-reporting gate.
     * Returns a boolean (not entity data) so scope enforcement is not required.
     */
    @Query("SELECT CASE WHEN COUNT(w) > 0 THEN TRUE ELSE FALSE END FROM WorkOrder w " +
           "WHERE w.assignedTechnicianId = :technicianId AND w.state IN :states")
    boolean existsActiveJobForTechnician(@Param("technicianId") UUID technicianId,
                                         @Param("states") Collection<WorkOrderState> states);
}
