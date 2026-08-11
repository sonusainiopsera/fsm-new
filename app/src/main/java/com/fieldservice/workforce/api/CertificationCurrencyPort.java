package com.fieldservice.workforce.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public read port for certification currency — consumed by dispatch and eligibility queries.
 *
 * <p>All methods evaluate currency at query time using {@code expires_on >= atDate}.
 * No cached boolean is ever consulted. A null {@code expires_on} means perpetual.
 *
 * <p>Fail-safe: any exception during evaluation propagates to the caller; the caller
 * must treat an exception as ineligible (guard never fails open).
 */
public interface CertificationCurrencyPort {

    /**
     * Returns {@code true} when the technician holds an active certification for
     * {@code certificationTypeCode} whose {@code expires_on >= atDate}.
     */
    boolean isCurrent(UUID technicianId, String certificationTypeCode, LocalDate atDate);

    /**
     * Returns all active, current certifications for the technician at {@code atDate}.
     */
    List<CertificationRef> currentCertifications(UUID technicianId, LocalDate atDate);

    /**
     * Returns the subset of {@code candidateTechnicianIds} where every code in
     * {@code requiredTypeCodes} is satisfied by a current certification at {@code atDate}.
     *
     * <p>Implemented as a single grouped SQL query (HAVING COUNT DISTINCT) — one round trip.
     *
     * <p>An empty {@code requiredTypeCodes} set returns an empty set (explicit semantics:
     * no requirement → no match, not all-match).
     */
    Set<UUID> technicianIdsWithCurrentCertifications(
            Set<String> requiredTypeCodes, LocalDate atDate);
}
