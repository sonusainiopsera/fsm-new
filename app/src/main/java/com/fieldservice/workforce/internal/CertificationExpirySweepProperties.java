package com.fieldservice.workforce.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuration properties for the certification expiry alert sweep.
 *
 * <p>Bound from the {@code app.cert.sweep.*} namespace. All properties are
 * externalised so operations can tune window sizes and batch size without
 * redeploying.
 */
@ConfigurationProperties(prefix = "app.cert.sweep")
@Validated
public class CertificationExpirySweepProperties {

    /** Spring cron expression for the daily sweep. Default: 02:00 UTC daily. */
    @NotBlank
    private String cron = "0 0 2 * * *";

    /** Days before expiry that triggers a WARNING alert. Default 30. */
    @Min(1)
    @Max(365)
    private int warningWindowDays = 30;

    /** Days before expiry that triggers an URGENT alert. Default 7. */
    @Min(1)
    @Max(30)
    private int urgentWindowDays = 7;

    /** Maximum certifications processed per sweep batch. Default 500. */
    @Min(1)
    @Max(10_000)
    private int batchSize = 500;

    /** When false, the scheduled method is a no-op (kill-switch). */
    private boolean enabled = true;

    /** When false, the distributed advisory lock is skipped (useful in tests). */
    private boolean lockEnabled = true;

    public String getCron()                         { return cron; }
    public void   setCron(String cron)              { this.cron = cron; }
    public int    getWarningWindowDays()            { return warningWindowDays; }
    public void   setWarningWindowDays(int d)       { this.warningWindowDays = d; }
    public int    getUrgentWindowDays()             { return urgentWindowDays; }
    public void   setUrgentWindowDays(int d)        { this.urgentWindowDays = d; }
    public int    getBatchSize()                    { return batchSize; }
    public void   setBatchSize(int batchSize)       { this.batchSize = batchSize; }
    public boolean isEnabled()                      { return enabled; }
    public void    setEnabled(boolean enabled)      { this.enabled = enabled; }
    public boolean isLockEnabled()                  { return lockEnabled; }
    public void    setLockEnabled(boolean l)        { this.lockEnabled = l; }
}
