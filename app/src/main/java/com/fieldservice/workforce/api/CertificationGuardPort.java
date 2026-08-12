package com.fieldservice.workforce.api;

import com.fieldservice.platform.api.exception.CertificationNotCurrentException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hard assignment guard for certification eligibility.
 *
 * <p>Called by dispatch and workorder assignment paths before every assignment.
 * The guard is fail-closed: any evaluation error denies eligibility and logs a
 * security-relevant event — it never defaults to eligible.
 *
 * <h3>Regulated vs. non-regulated</h3>
 * <ul>
 *   <li><strong>Regulated</strong> ({@code certification_type.regulated = true}):
 *       a missing or expired certification raises {@link CertificationNotCurrentException}
 *       mapping to HTTP 422.  No override parameter exists.</li>
 *   <li><strong>Non-regulated</strong>: a missing or expired certification is collected
 *       in the returned warnings list.  Assignment proceeds.</li>
 * </ul>
 */
public interface CertificationGuardPort {

    /**
     * Asserts that the technician holds a current certification for every required type.
     *
     * @param technicianId      technician to evaluate
     * @param requiredTypeCodes set of certification type codes required for the job
     * @param atDate            evaluation date
     * @return list of advisory warnings for non-regulated missing certifications (may be empty)
     * @throws CertificationNotCurrentException if any REGULATED required certification
     *         is not current — maps to HTTP 422 with the missing type codes in the response
     */
    List<String> assertAssignable(UUID technicianId, Set<String> requiredTypeCodes, LocalDate atDate);
}
