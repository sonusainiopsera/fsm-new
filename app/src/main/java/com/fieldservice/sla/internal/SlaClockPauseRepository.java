package com.fieldservice.sla.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Package-private JPA repository for SLA clock pause rows.
 */
interface SlaClockPauseRepository extends JpaRepository<SlaClockPause, UUID> {

    /** Returns the open pause for the given work order, if any. */
    Optional<SlaClockPause> findByWorkOrderIdAndResumedAtIsNull(UUID workOrderId);

    /** Returns all pause rows for a work order (open and closed), for effective-deadline computation. */
    List<SlaClockPause> findByWorkOrderId(UUID workOrderId);
}
