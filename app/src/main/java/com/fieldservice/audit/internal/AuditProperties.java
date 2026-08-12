package com.fieldservice.audit.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the audit trail search and export surface.
 *
 * <p>Retention is configuration-driven with a documented minimum of one year (365 days)
 * and a target of 24 months. No query or purge routine may hard-code a retention window.
 */
@ConfigurationProperties(prefix = "audit.trail")
record AuditProperties(
        /**
         * Minimum retention in days. Revisions older than this value may be archived.
         * One-year minimum (365) documented; 24-month target (730) recommended.
         */
        int retentionDays,

        /**
         * Maximum rows for a synchronous export. Requests exceeding this ceiling
         * are generated asynchronously with a status-polling handle.
         */
        int exportRowCeiling) {

    AuditProperties {
        if (retentionDays < 365) {
            throw new IllegalArgumentException(
                    "audit.trail.retention-days must be >= 365 (one-year minimum); got: " + retentionDays);
        }
        if (exportRowCeiling < 1) {
            throw new IllegalArgumentException(
                    "audit.trail.export-row-ceiling must be >= 1; got: " + exportRowCeiling);
        }
    }
}
