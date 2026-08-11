package com.fieldservice.domain.assignment;

import com.fieldservice.platform.persistence.ScopedRepository;

import java.util.UUID;

/**
 * Repository for {@link Assignment} entities.
 *
 * <p>All reads must route through {@link com.fieldservice.platform.persistence.ScopedQueryExecutor}.
 */
public interface AssignmentRepository extends ScopedRepository<Assignment, UUID> {
}
