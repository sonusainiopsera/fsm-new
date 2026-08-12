package com.fieldservice.aiaudit.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AI interaction audit module.
 *
 * <p>Retention periods are indicative defaults pending ratification by the Data
 * Governance team; the values MUST be externally configured rather than hard-coded
 * so they can be updated without a code change.
 */
@ConfigurationProperties(prefix = "ai.audit")
record AiAuditProperties(
        /** Days to retain copilot interaction records. Default 365 (indicative, pending ratification). */
        int copilotRetentionDays,
        /** Days to retain photo-caption interaction records. Default 90 (indicative, pending ratification). */
        int photoCaptionRetentionDays,
        /** Maximum characters of prompt text stored; longer prompts are truncated. */
        int maxPromptChars,
        /** Maximum characters of response text stored; longer responses are truncated. */
        int maxResponseChars,
        /** Batch size for the daily purge job. */
        int purgeJobBatchSize
) {
    AiAuditProperties {
        if (copilotRetentionDays < 1)    throw new IllegalArgumentException("copilotRetentionDays must be >= 1");
        if (photoCaptionRetentionDays < 1) throw new IllegalArgumentException("photoCaptionRetentionDays must be >= 1");
        if (maxPromptChars < 100)        throw new IllegalArgumentException("maxPromptChars must be >= 100");
        if (maxResponseChars < 100)      throw new IllegalArgumentException("maxResponseChars must be >= 100");
        if (purgeJobBatchSize < 1)       throw new IllegalArgumentException("purgeJobBatchSize must be >= 1");
    }
}
