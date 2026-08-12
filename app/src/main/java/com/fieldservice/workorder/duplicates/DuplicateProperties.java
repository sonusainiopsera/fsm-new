package com.fieldservice.workorder.duplicates;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration-driven duplicate detection parameters.
 * Changing these values changes detection behaviour without redeployment.
 */
@ConfigurationProperties(prefix = "duplicate")
public record DuplicateProperties(int windowHours, double minSignatureOverlap) {

    public DuplicateProperties {
        if (windowHours <= 0) throw new IllegalArgumentException("duplicate.window-hours must be positive");
        if (minSignatureOverlap < 0.0 || minSignatureOverlap > 1.0) {
            throw new IllegalArgumentException("duplicate.min-signature-overlap must be in [0.0, 1.0]");
        }
    }
}
