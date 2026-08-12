package com.fieldservice.domain.assignment;

import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Assignment} entities.
 *
 * <p>All reads must route through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
public interface AssignmentRepository extends ScopedRepository<Assignment, UUID> {

    /**
     * Returns the active assignment for a work order — the row where {@code end_at IS NULL}.
     * Uses an unscoped query because this is called from service internals that have already
     * validated authorization.
     */
    @Query("SELECT a FROM Assignment a WHERE a.workOrderId = :workOrderId AND a.endAt IS NULL")
    Optional<Assignment> findActiveByWorkOrderId(@Param("workOrderId") UUID workOrderId);

    /**
     * Returns the complete ordered assignment history for a work order, oldest first.
     * Includes both active and superseded assignments.
     */
    @Query("SELECT a FROM Assignment a WHERE a.workOrderId = :workOrderId ORDER BY a.assignedAt ASC")
    List<Assignment> findHistoryByWorkOrderId(@Param("workOrderId") UUID workOrderId);
}
