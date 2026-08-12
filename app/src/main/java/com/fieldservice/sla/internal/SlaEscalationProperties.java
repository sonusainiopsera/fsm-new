package com.fieldservice.sla.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the SLA escalation subsystem.
 *
 * <p>Bound to {@code app.sla.escalation.*} properties.
 */
@ConfigurationProperties(prefix = "app.sla.escalation")
class SlaEscalationProperties {

    /** Seconds within which a second notification for the same work order and recipient
     *  is suppressed (deduplication window). Default 1800s (30 min). */
    private int suppressionWindowSeconds = 1800;

    /** Cron expression for the grace-period escalation checker. Default: every 5 minutes. */
    private String gracePeriodCron = "0 */5 * * * *";

    /** URL template for work order deep links. {ref} is substituted with the WO reference. */
    private String deepLinkTemplate = "/work-orders?ref={ref}";

    /** When false, the grace-period escalation checker is a no-op. */
    private boolean enabled = true;

    public int getSuppressionWindowSeconds() { return suppressionWindowSeconds; }
    public void setSuppressionWindowSeconds(int v) { this.suppressionWindowSeconds = v; }

    public String getGracePeriodCron() { return gracePeriodCron; }
    public void setGracePeriodCron(String v) { this.gracePeriodCron = v; }

    public String getDeepLinkTemplate() { return deepLinkTemplate; }
    public void setDeepLinkTemplate(String v) { this.deepLinkTemplate = v; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
}
