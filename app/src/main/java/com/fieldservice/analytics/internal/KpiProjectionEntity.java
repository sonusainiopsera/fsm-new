package com.fieldservice.analytics.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code kpi_projection} table.
 *
 * <p>Package-private: nothing outside {@code analytics.internal} may import this class.
 * The public surface is {@link com.fieldservice.analytics.KpiProjection}.
 *
 * <p>No @Version / optimistic locking: projections are written by a single worker
 * replica holding the distributed lease, so concurrent upsert conflicts are not
 * expected. The business {@code projectionVersion} column is bumped by the service.
 */
@Entity
@Table(name = "kpi_projection")
class KpiProjectionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "metric_key", nullable = false, length = 100)
    private String metricKey;

    @Column(name = "segment_key", nullable = false, length = 100)
    private String segmentKey;

    @Column(name = "window_key", nullable = false, length = 50)
    private String windowKey;

    @Nullable
    @Column(name = "numerator", precision = 20, scale = 4)
    private BigDecimal numerator;

    @Nullable
    @Column(name = "denominator", precision = 20, scale = 4)
    private BigDecimal denominator;

    @Nullable
    @Column(name = "value", precision = 20, scale = 4)
    private BigDecimal value;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "maturity", nullable = false, length = 20)
    private String maturity = "CURRENT";

    @Column(name = "data_as_of", nullable = false)
    private Instant dataAsOf;

    @Column(name = "projection_version", nullable = false)
    private long projectionVersion = 1L;

    @Column(name = "degraded", nullable = false)
    private boolean degraded;

    @Nullable
    @Column(name = "degraded_reason")
    private String degradedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KpiProjectionEntity() {}

    KpiProjectionEntity(UUID id, String metricKey, String segmentKey, String windowKey, Instant now) {
        this.id = id;
        this.metricKey = metricKey;
        this.segmentKey = segmentKey;
        this.windowKey = windowKey;
        this.dataAsOf = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() { return id; }
    String getMetricKey() { return metricKey; }
    String getSegmentKey() { return segmentKey; }
    String getWindowKey() { return windowKey; }
    @Nullable BigDecimal getNumerator() { return numerator; }
    @Nullable BigDecimal getDenominator() { return denominator; }
    @Nullable BigDecimal getValue() { return value; }
    int getSampleCount() { return sampleCount; }
    String getMaturity() { return maturity; }
    Instant getDataAsOf() { return dataAsOf; }
    long getProjectionVersion() { return projectionVersion; }
    boolean isDegraded() { return degraded; }
    @Nullable String getDegradedReason() { return degradedReason; }

    void setValue(@Nullable BigDecimal value) { this.value = value; }
    void setNumerator(@Nullable BigDecimal numerator) { this.numerator = numerator; }
    void setDenominator(@Nullable BigDecimal denominator) { this.denominator = denominator; }
    void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    void setDataAsOf(Instant dataAsOf) { this.dataAsOf = dataAsOf; this.updatedAt = dataAsOf; }
    void bumpProjectionVersion() { this.projectionVersion++; }
    void setDegraded(boolean degraded) { this.degraded = degraded; }
    void setDegradedReason(@Nullable String reason) { this.degradedReason = reason; }
}
