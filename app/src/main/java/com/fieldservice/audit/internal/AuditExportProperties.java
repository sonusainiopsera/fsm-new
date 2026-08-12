package com.fieldservice.audit.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for audit trail export (WO-199).
 *
 * <p>Retention period defaults are indicative and pending compliance ratification.
 * The minimum floor is one year; the 24-month target is the design goal.
 */
@ConfigurationProperties(prefix = "app.audit")
public class AuditExportProperties {

    /**
     * Retention days for audit records (indicative — minimum 365, target 730).
     * Pending compliance ratification.
     */
    private int retentionDays = 365;

    /**
     * Row ceiling for synchronous export; requests above this are handled asynchronously.
     */
    private int syncExportRowCeiling = 5000;

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int v) { this.retentionDays = v; }

    public int getSyncExportRowCeiling() { return syncExportRowCeiling; }
    public void setSyncExportRowCeiling(int v) { this.syncExportRowCeiling = v; }
}
