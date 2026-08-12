package com.fieldservice.workforce.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Framework-free cohort classifier for certification expiry alerts.
 *
 * <p>Classifies a single certification into one of three alert stages — or returns
 * empty when no alert is due — using only the configured window sizes and the
 * business date supplied by the caller. Eligibility is always derived from
 * {@code expires_on} at query time; no cached currency flag is used (WO-023 rule).
 *
 * <h3>Classification rules</h3>
 * <ol>
 *   <li>{@code expires_on IS NULL} → no cohort (null never enters any alert cohort)</li>
 *   <li>{@code daysToExpiry < 0} → {@link CertificationAlertStage#EXPIRED}
 *       (already expired at business date)</li>
 *   <li>{@code daysToExpiry == 0} → {@link CertificationAlertStage#EXPIRED}
 *       (expires today)</li>
 *   <li>{@code daysToExpiry <= urgentWindowDays} → {@link CertificationAlertStage#URGENT}</li>
 *   <li>{@code daysToExpiry <= warningWindowDays} → {@link CertificationAlertStage#WARNING}</li>
 *   <li>otherwise → empty (outside all windows)</li>
 * </ol>
 *
 * <p>Edge case: a certification exactly at the warning threshold (e.g., 30 days when
 * the window is 30) is classified as WARNING; one day beyond (31) returns empty.
 */
class CohortClassifier {

    private final int warningWindowDays;
    private final int urgentWindowDays;

    CohortClassifier(int warningWindowDays, int urgentWindowDays) {
        if (urgentWindowDays >= warningWindowDays) {
            throw new IllegalArgumentException(
                    "urgentWindowDays must be strictly less than warningWindowDays");
        }
        this.warningWindowDays = warningWindowDays;
        this.urgentWindowDays  = urgentWindowDays;
    }

    /**
     * Classifies a certification by its expiry date relative to {@code businessDate}.
     *
     * @param expiresOn    the certification expiry date, or {@code null}
     * @param businessDate the current business date (supplied by caller — not {@code LocalDate.now()})
     * @return the alert stage, or empty if no alert is due
     */
    Optional<CertificationAlertStage> classify(LocalDate expiresOn, LocalDate businessDate) {
        if (expiresOn == null) {
            return Optional.empty();
        }

        long daysToExpiry = ChronoUnit.DAYS.between(businessDate, expiresOn);

        if (daysToExpiry <= 0) {
            return Optional.of(CertificationAlertStage.EXPIRED);
        }
        if (daysToExpiry <= urgentWindowDays) {
            return Optional.of(CertificationAlertStage.URGENT);
        }
        if (daysToExpiry <= warningWindowDays) {
            return Optional.of(CertificationAlertStage.WARNING);
        }
        return Optional.empty();
    }
}
