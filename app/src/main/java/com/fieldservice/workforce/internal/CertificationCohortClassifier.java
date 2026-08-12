package com.fieldservice.workforce.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Framework-free, pure cohort classifier.
 *
 * <p>Maps a certification's {@code expiresOn} date to the most urgent alert stage
 * that applies on the given business date, using configurable window sizes.
 *
 * <p>Boundary rules (inclusive):
 * <ul>
 *   <li>EXPIRED: {@code expiresOn < today}  (daysRemaining < 0)</li>
 *   <li>URGENT:  {@code 0 <= daysRemaining <= urgentDays}</li>
 *   <li>WARNING: {@code urgentDays < daysRemaining <= warningDays}</li>
 *   <li>None:    {@code daysRemaining > warningDays}</li>
 * </ul>
 *
 * <p>A {@code null} {@code expiresOn} means perpetual competency and never enters any cohort.
 */
final class CertificationCohortClassifier {

    private CertificationCohortClassifier() {}

    /**
     * Classifies a certification's expiry relative to today.
     *
     * @param expiresOn    expiry date, or {@code null} for perpetual certifications
     * @param today        business date (caller-supplied so tests are deterministic)
     * @param warningDays  number of days before expiry to enter the WARNING cohort (default 30)
     * @param urgentDays   number of days before expiry to enter the URGENT cohort (default 7)
     * @return the most urgent applicable stage, or empty if outside all alert windows
     */
    static Optional<CertificationAlertStage> classify(
            LocalDate expiresOn, LocalDate today, int warningDays, int urgentDays) {

        if (expiresOn == null) {
            return Optional.empty();
        }

        long daysRemaining = ChronoUnit.DAYS.between(today, expiresOn);

        if (daysRemaining < 0) {
            return Optional.of(CertificationAlertStage.EXPIRED);
        }
        if (daysRemaining <= urgentDays) {
            return Optional.of(CertificationAlertStage.URGENT);
        }
        if (daysRemaining <= warningDays) {
            return Optional.of(CertificationAlertStage.WARNING);
        }
        return Optional.empty();
    }
}
