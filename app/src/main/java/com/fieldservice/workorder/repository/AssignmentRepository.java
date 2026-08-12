package com.fieldservice.workorder.repository;

import com.fieldservice.workorder.domain.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    /** Returns the current active assignment (end_at IS NULL) for a work order. */
    Optional<Assignment> findByWorkOrderIdAndEndAtIsNull(UUID workOrderId);

    /** Returns the complete assignment history for a work order, oldest first. */
    List<Assignment> findByWorkOrderIdOrderByCreatedAtAsc(UUID workOrderId);
}
