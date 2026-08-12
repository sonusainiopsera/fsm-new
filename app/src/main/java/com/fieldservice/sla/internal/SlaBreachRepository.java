package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-write breach repository.
 *
 * <p>No {@code deleteById} or {@code delete} methods are declared — breach records are
 * append-and-revise only. The inherited {@link JpaRepository} delete methods remain
 * package-private and are never exposed through a public API surface.
 *
 * <p>Paginated listing with priority filtering uses JdbcTemplate in
 * {@link SlaBreachService} to avoid a cartesian JOIN through an unmapped workOrderId FK.
 */
@Repository
interface SlaBreachRepository extends JpaRepository<SlaBreachEntity, UUID> {

    Optional<SlaBreachEntity> findByWorkOrderIdAndBreachType(UUID workOrderId, String breachType);

    /** Used by the finalisation hook to find all unfinalised breaches for a work order. */
    List<SlaBreachEntity> findByWorkOrderIdAndFinalOverrunMinutesIsNull(UUID workOrderId);
}
