package com.fieldservice.workforce.internal;

/**
 * Alert stage for a pre-expiry certification alert sweep.
 *
 * <p>Stages map to the three cohorts produced by {@link CohortClassifier}:
 * <ul>
 *   <li>{@link #WARNING} — expires within the configurable warning window (default 30 days)</li>
 *   <li>{@link #URGENT}  — expires within the configurable urgent window (default 7 days)</li>
 *   <li>{@link #EXPIRED} — expiry date is in the past</li>
 * </ul>
 *
 * <p>A certification that has crossed both the warning and urgent thresholds alerts
 * at URGENT, not at both (see {@link CohortClassifier#classify}).
 */
enum CertificationAlertStage {
    WARNING, URGENT, EXPIRED
}
