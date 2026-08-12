package com.fieldservice.workorder.duplicates;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for duplicate work order detection.
 *
 * <p>All thresholds are configuration-driven so they can be changed without a deploy.
 */
@ConfigurationProperties(prefix = "app.duplicate")
public class DuplicateDetectionProperties {

    /** Hours back from now to search for open work orders in the same customer account. */
    private int windowHours = 72;

    /** Minimum fault-signature token overlap to include a candidate via signature match. */
    private int minSignatureOverlap = 1;

    /** Maximum number of candidates returned per detection call. */
    private int maxCandidates = 5;

    public int getWindowHours() { return windowHours; }
    public void setWindowHours(int windowHours) { this.windowHours = windowHours; }

    public int getMinSignatureOverlap() { return minSignatureOverlap; }
    public void setMinSignatureOverlap(int minSignatureOverlap) { this.minSignatureOverlap = minSignatureOverlap; }

    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
}
