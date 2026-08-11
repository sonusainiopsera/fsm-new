package com.fieldservice.privacy.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the DSAR request workflow.
 *
 * <p>Bound from the {@code privacy.dsar} prefix; all values have safe defaults.
 */
@Component
@ConfigurationProperties(prefix = "privacy.dsar")
public class DsarProperties {

    /** SLA window in days (default 30). Due date = submitted_at + slaDays. */
    private int slaDays = 30;

    /** Days remaining below which a request is considered at-risk (default 5). */
    private int atRiskThresholdDays = 5;

    /** Maximum presigned download URL validity in seconds (must not exceed 300). */
    private int downloadUrlExpirySeconds = 300;

    /** Maximum page size for the DSAR queue list endpoint. */
    private int maxPageSize = 50;

    public int getSlaDays() { return slaDays; }
    public void setSlaDays(int slaDays) { this.slaDays = slaDays; }

    public int getAtRiskThresholdDays() { return atRiskThresholdDays; }
    public void setAtRiskThresholdDays(int atRiskThresholdDays) {
        this.atRiskThresholdDays = atRiskThresholdDays;
    }

    public int getDownloadUrlExpirySeconds() { return Math.min(downloadUrlExpirySeconds, 300); }
    public void setDownloadUrlExpirySeconds(int downloadUrlExpirySeconds) {
        this.downloadUrlExpirySeconds = downloadUrlExpirySeconds;
    }

    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
}
