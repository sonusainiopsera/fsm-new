package com.fieldservice.workforce.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure-function currency evaluator for technician certifications — no Spring, no JPA.
 *
 * <p>Currency predicate: {@code expires_on >= atDate} (inclusive of expiry date).
 * Boundary semantics: certified until the 30th means eligible on the 30th and ineligible on the 1st.
 * A {@code null} expires_on means perpetual — always current.
 * No grace period exists for any certification type (regulated or not).
 *
 * <p>Fail-safe: a technician with no matching, active certification record is never current.
 */
public class CertificationEvaluator {

    /** Input value object for a single certification snapshot used in evaluation. */
    public record CertSnapshot(
            String typeCode,
            boolean regulated,
            boolean active,
            LocalDate issuedOn,
            LocalDate expiresOn  // null = perpetual
    ) {}

    /**
     * Returns {@code true} when {@code snapshots} contains at least one active
     * record for {@code typeCode} that is current at {@code atDate}.
     */
    public boolean isCurrent(List<CertSnapshot> snapshots, String typeCode, LocalDate atDate) {
        return snapshots.stream()
                .filter(s -> s.active() && typeCode.equals(s.typeCode()))
                .anyMatch(s -> isCurrent(s, atDate));
    }

    /**
     * Returns the subset of required type codes that are NOT currently satisfied.
     * An empty returned set means all requirements are met.
     */
    public Set<String> findMissingCodes(List<CertSnapshot> snapshots,
                                        Set<String> requiredTypeCodes,
                                        LocalDate atDate) {
        return requiredTypeCodes.stream()
                .filter(code -> !isCurrent(snapshots, code, atDate))
                .collect(Collectors.toSet());
    }

    /**
     * Derives {@code daysUntilExpiry} for the most-recent current snapshot of a type.
     * Returns {@code null} when {@code expiresOn} is null (perpetual).
     */
    public Long daysUntilExpiry(LocalDate expiresOn, LocalDate atDate) {
        if (expiresOn == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(atDate, expiresOn);
    }

    private boolean isCurrent(CertSnapshot snap, LocalDate atDate) {
        if (snap.expiresOn() == null) {
            return true;
        }
        // inclusive: certified until the 30th means eligible on the 30th
        return !snap.expiresOn().isBefore(atDate);
    }
}
