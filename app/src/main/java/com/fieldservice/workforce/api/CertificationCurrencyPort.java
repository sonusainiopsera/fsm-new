package com.fieldservice.workforce.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public seam for dispatch to query technician certification currency.
 *
 * <h3>Currency contract</h3>
 * A certification is current when:
 * <ul>
 *   <li>its {@code active} flag is {@code true}, and</li>
 *   <li>its {@code expires_on} is {@code NULL} (perpetual) or {@code >= atDate}, and</li>
 *   <li>the owning technician is active.</li>
 * </ul>
 *
 * <p>Currency is ALWAYS computed as a query-time predicate.
 * No cached boolean is ever stored or read.
 *
 * <h3>Boundary semantics</h3>
 * A certification with {@code expires_on = atDate} is current on that date.
 * The same certification evaluated on {@code atDate + 1 day} is NOT current.
 * No grace period is applied for any certification type.
 *
 * <h3>Empty required-type set</h3>
 * {@link #technicianIdsWithCurrentCertifications} with an empty {@code requiredTypeCodes}
 * set returns an empty set — all technicians are eligible when no types are required.
 */
public interface CertificationCurrencyPort {

    /**
     * Returns {@code true} iff the technician holds a current certification of the
     * given type code on {@code atDate}.
     *
     * @param technicianId          technician to check
     * @param certificationTypeCode type code to look up in the registry
     * @param atDate                evaluation date (canonical business date, no time zone)
     */
    boolean isCurrent(UUID technicianId, String certificationTypeCode, LocalDate atDate);

    /**
     * Returns all current certifications for the technician on {@code atDate},
     * including derived {@code current} flag and {@code daysUntilExpiry}.
     */
    List<CertificationSummary> currentCertifications(UUID technicianId, LocalDate atDate);

    /**
     * Returns the IDs of technicians who hold a current certification for EVERY
     * code in {@code requiredTypeCodes} on {@code atDate}.
     *
     * <p>Implemented as a single grouped SQL query (one round-trip) so this can
     * be called on the full dispatch candidate pool without N+1 queries.
     *
     * @param requiredTypeCodes set of certification type codes; empty set returns empty set
     * @param atDate            evaluation date
     * @return IDs of eligible technicians (may be empty, never null)
     */
    Set<UUID> technicianIdsWithCurrentCertifications(Set<String> requiredTypeCodes, LocalDate atDate);
}
