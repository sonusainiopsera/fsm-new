package com.fieldservice.aiaudit.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI interaction audit log (WO-180).
 * Retention periods are indicative and pending compliance ratification.
 */
@ConfigurationProperties(prefix = "app.ai.audit")
public class AiAuditProperties {

    /** Retention days for COPILOT_QUESTION interactions (indicative — pending ratification). */
    private int copilotRetentionDays = 365;

    /** Retention days for PHOTO_CAPTION interactions (indicative — pending ratification). */
    private int photoCaptionRetentionDays = 90;

    /** Max characters stored for redacted_prompt and response_text. */
    private int maxTextChars = 4000;

    /** Batch size for the daily purge job. */
    private int purgeBatchSize = 500;

    public int getCopilotRetentionDays() { return copilotRetentionDays; }
    public void setCopilotRetentionDays(int v) { this.copilotRetentionDays = v; }

    public int getPhotoCaptionRetentionDays() { return photoCaptionRetentionDays; }
    public void setPhotoCaptionRetentionDays(int v) { this.photoCaptionRetentionDays = v; }

    public int getMaxTextChars() { return maxTextChars; }
    public void setMaxTextChars(int v) { this.maxTextChars = v; }

    public int getPurgeBatchSize() { return purgeBatchSize; }
    public void setPurgeBatchSize(int v) { this.purgeBatchSize = v; }

    public int retentionDaysFor(String interactionType) {
        return "PHOTO_CAPTION".equals(interactionType)
                ? photoCaptionRetentionDays
                : copilotRetentionDays;
    }
}
