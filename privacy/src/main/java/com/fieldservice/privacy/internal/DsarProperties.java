package com.fieldservice.privacy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the DSAR workflow and export job.
 */
@ConfigurationProperties(prefix = "app.privacy.dsar")
class DsarProperties {

    /** Retention period for a data subject access request (ISO-8601 duration). */
    private Duration requestDueDays = Duration.ofDays(30);

    /** Days remaining before a request is flagged as at-risk. */
    private int atRiskThresholdDays = 7;

    /** Maximum validity for a download token in seconds. */
    private int downloadTokenValiditySecs = 300;

    /** Cron expression for the export assembly job. */
    private String exportCron = "0 */5 * * * *";

    /** Lock lease seconds for the export job distributed lock. */
    private int exportLockLeaseSecs = 300;

    /** Zone for computing remaining days and due dates. */
    private String zone = "UTC";

    public Duration getRequestDueDays()           { return requestDueDays; }
    public int      getAtRiskThresholdDays()       { return atRiskThresholdDays; }
    public int      getDownloadTokenValiditySecs() { return downloadTokenValiditySecs; }
    public String   getExportCron()               { return exportCron; }
    public int      getExportLockLeaseSecs()      { return exportLockLeaseSecs; }
    public String   getZone()                     { return zone; }

    public void setRequestDueDays(Duration v)          { this.requestDueDays = v; }
    public void setAtRiskThresholdDays(int v)          { this.atRiskThresholdDays = v; }
    public void setDownloadTokenValiditySecs(int v)    { this.downloadTokenValiditySecs = v; }
    public void setExportCron(String v)                { this.exportCron = v; }
    public void setExportLockLeaseSecs(int v)          { this.exportLockLeaseSecs = v; }
    public void setZone(String v)                      { this.zone = v; }
}
