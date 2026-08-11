package com.fieldservice.portal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for portal service-request submission (WO-170).
 *
 * <p>Bound from the {@code app.portal.submission.*} namespace.
 */
@ConfigurationProperties(prefix = "app.portal.submission")
@Validated
public class PortalSubmissionProperties {

    /**
     * Default priority assigned to portal submissions.
     * Customers cannot override this — dispatchers may adjust post-creation.
     */
    @NotBlank
    @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL",
             message = "defaultPriority must be one of LOW, MEDIUM, HIGH, CRITICAL")
    private String defaultPriority = "MEDIUM";

    // ── Rate limiting ─────────────────────────────────────────────────────────

    /** Maximum portal submissions per account per rate-limit window. */
    @Min(1)
    @Max(1000)
    private int rateLimitMax = 10;

    /** Duration of the rate-limit window in seconds. */
    @Min(1)
    private long rateLimitWindowSeconds = 60L;

    public String getDefaultPriority() { return defaultPriority; }
    public void setDefaultPriority(String defaultPriority) { this.defaultPriority = defaultPriority; }

    public int getRateLimitMax() { return rateLimitMax; }
    public void setRateLimitMax(int rateLimitMax) { this.rateLimitMax = rateLimitMax; }

    public long getRateLimitWindowSeconds() { return rateLimitWindowSeconds; }
    public void setRateLimitWindowSeconds(long rateLimitWindowSeconds) { this.rateLimitWindowSeconds = rateLimitWindowSeconds; }
}
