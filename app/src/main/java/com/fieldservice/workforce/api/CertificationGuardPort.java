package com.fieldservice.workforce.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public guard port for certification-based dispatch eligibility.
 *
 * <p>Regulated shortfalls throw {@link CertificationNotCurrentException} (422).
 * Non-regulated shortfalls are returned as advisory warnings — no exception is thrown.
 *
 * <p>No override parameter exists on any path. The absence of the parameter is the enforcement.
 */
public interface CertificationGuardPort {

    /**
     * Asserts that {@code technicianId} holds a current certification for every code in
     * {@code requiredTypeCodes} at {@code atDate}.
     *
     * <p>Regulated missing codes throw {@link CertificationNotCurrentException} carrying
     * all missing regulated codes at once. Non-regulated missing codes are returned as
     * advisory warning strings and do not throw.
     *
     * @return advisory warning messages for non-regulated shortfalls (may be empty)
     * @throws CertificationNotCurrentException if any regulated type is not current
     */
    List<String> assertAssignable(UUID technicianId, Set<String> requiredTypeCodes, LocalDate atDate);
}
