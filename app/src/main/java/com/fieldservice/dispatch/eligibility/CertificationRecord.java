package com.fieldservice.dispatch.eligibility;

import java.time.LocalDate;

/**
 * Immutable dispatch-owned snapshot of a technician certification.
 * Currency is evaluated by EligibilityFilter at evaluation time — never stored.
 */
record CertificationRecord(String typeCode, LocalDate expiresOn) {}
