package com.fieldservice.portal.csat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CSAT survey configuration.
 *
 * <p>Properties prefix: {@code app.csat}
 */
@ConfigurationProperties(prefix = "app.csat")
public record CsatProperties(
        /** Number of days after issuance within which the customer may submit a response. */
        int responseWindowDays
) {
    public CsatProperties {
        if (responseWindowDays < 1) {
            throw new IllegalArgumentException("responseWindowDays must be at least 1");
        }
    }

    public CsatProperties() {
        this(7);
    }
}
