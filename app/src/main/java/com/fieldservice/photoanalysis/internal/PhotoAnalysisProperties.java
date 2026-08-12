package com.fieldservice.photoanalysis.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the photo analysis feature.
 *
 * <p>Feature flag: {@code ai.photo-analysis.enabled} (default {@code false}).
 * When disabled, the analysis endpoint returns 503 with a standard degraded body
 * and the plain description path remains available.
 *
 * <p>Retention: {@code ai.photo-analysis.retention-days} (indicative default 730 days /
 * 24 months; pending ratification per BR-31).
 */
@ConfigurationProperties(prefix = "ai.photo-analysis")
public record PhotoAnalysisProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("730")   int retentionDays,
        @DefaultValue("gpt-4-vision-preview") String provider,
        @DefaultValue("1")     int purgeBatchSize
) {}
