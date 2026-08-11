package com.fieldservice.workforce.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public read port for the workforce module consumed by dispatch and other modules.
 *
 * <p>Only read operations are exposed. Entities and repositories remain package-private.
 */
public interface TechnicianDirectoryPort {

    Optional<TechnicianSummary> findById(UUID technicianId);

    List<TechnicianSummary> findAllActive();
}
