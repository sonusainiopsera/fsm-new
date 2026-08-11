package com.fieldservice.workforce.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port for dispatch to query technician profiles.
 * Entities and repositories are package-private in {@code workforce.internal} and
 * must not be accessed directly by callers of this port.
 */
public interface TechnicianDirectoryPort {

    /**
     * Returns all active technicians.  Used by the dispatch scoring engine to build
     * the candidate pool before feasibility filtering.
     */
    List<TechnicianSummary> findAllActive();

    /**
     * Returns the technician with the given identity, or empty if not found.
     */
    Optional<TechnicianSummary> findById(UUID technicianId);

    /**
     * Returns the technician whose {@code userId} matches the given identity user id,
     * or empty if no technician profile exists for that user.
     */
    Optional<TechnicianSummary> findByUserId(UUID userId);
}
