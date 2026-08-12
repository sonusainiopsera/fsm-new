package com.fieldservice.workforce.internal;

/**
 * Alert stage for a certification approaching or past its expiry date.
 *
 * <p>Stages are ordered by increasing urgency. A certification that crosses multiple
 * thresholds over time raises each stage at most once per validity period.
 */
enum CertificationAlertStage {
    /** Expiry is within the warning window (default 30 days). */
    WARNING,
    /** Expiry is within the urgent window (default 7 days, subset of warning window). */
    URGENT,
    /** Certification has already expired. */
    EXPIRED
}
