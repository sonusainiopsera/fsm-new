package com.fieldservice.analytics.internal.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing the {@code baseline_metric} table.
 *
 * <p>Populated manually (or by a future one-shot job) once the 30-day production
 * baseline window has elapsed. Until at least one row exists for a given
 * (metric_key, segment_key), the SLA projections report {@code BASELINE_PENDING}.
 */
@Entity
@Table(name = "baseline_metric")
public class BaselineMetricEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "metric_key", nullable = false, length = 100)
    private String metricKey;

    @Column(name = "segment_key", nullable = false, length = 100)
    private String segmentKey;

    @Column(name = "baseline_value", nullable = false, precision = 20, scale = 6)
    private BigDecimal baselineValue;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected BaselineMetricEntity() {}

    String     getMetricKey()     { return metricKey; }
    String     getSegmentKey()    { return segmentKey; }
    BigDecimal getBaselineValue() { return baselineValue; }
    int        getSampleSize()    { return sampleSize; }
    Instant    getCapturedAt()    { return capturedAt; }
}
