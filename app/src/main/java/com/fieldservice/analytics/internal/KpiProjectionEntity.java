package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity backing the {@code kpi_projection} table.
 *
 * <p>Package-private: callers use {@link com.fieldservice.analytics.KpiProjectionQuery} only.
 * Explicitly excluded from Envers auditing — this is derived data.
 */
@Entity
@Table(name = "kpi_projection")
class KpiProjectionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "metric_key",  nullable = false, updatable = false)
    private String metricKey;

    @Column(name = "segment_key", nullable = false, updatable = false)
    private String segmentKey;

    @Column(name = "window_key",  nullable = false, updatable = false)
    private String windowKey;

    @Column(name = "numerator")
    private BigDecimal numerator;

    @Column(name = "denominator")
    private BigDecimal denominator;

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "maturity", length = 20)
    private String maturity;

    @Column(name = "data_as_of", nullable = false)
    private Instant dataAsOf;

    @Column(name = "projection_version", nullable = false)
    private long projectionVersion = 1L;

    @Column(name = "degraded", nullable = false)
    private boolean degraded = false;

    @Column(name = "partial_bucket", nullable = false)
    private boolean partialBucket = false;

    @Column(name = "incomplete_data", nullable = false)
    private boolean incompleteData = false;

    protected KpiProjectionEntity() {}

    KpiProjectionEntity(UUID id, String metricKey, String segmentKey, String windowKey,
                        BigDecimal numerator, BigDecimal denominator, BigDecimal value,
                        Integer sampleCount, String maturity, Instant dataAsOf, boolean degraded) {
        this.id             = id;
        this.metricKey      = metricKey;
        this.segmentKey     = segmentKey;
        this.windowKey      = windowKey;
        this.numerator      = numerator;
        this.denominator    = denominator;
        this.value          = value;
        this.sampleCount    = sampleCount;
        this.maturity       = maturity;
        this.dataAsOf       = dataAsOf;
        this.degraded       = degraded;
    }

    UUID       getId()               { return id; }
    String     getMetricKey()        { return metricKey; }
    String     getSegmentKey()       { return segmentKey; }
    String     getWindowKey()        { return windowKey; }
    BigDecimal getNumerator()        { return numerator; }
    BigDecimal getDenominator()      { return denominator; }
    BigDecimal getValue()            { return value; }
    Integer    getSampleCount()      { return sampleCount; }
    String     getMaturity()         { return maturity; }
    Instant    getDataAsOf()         { return dataAsOf; }
    long       getProjectionVersion(){ return projectionVersion; }
    boolean    isDegraded()          { return degraded; }
    boolean    isPartialBucket()    { return partialBucket; }
    boolean    isIncompleteData()   { return incompleteData; }

    void setNumerator(BigDecimal n)        { this.numerator      = n; }
    void setDenominator(BigDecimal d)      { this.denominator    = d; }
    void setValue(BigDecimal v)            { this.value          = v; }
    void setSampleCount(Integer c)         { this.sampleCount    = c; }
    void setMaturity(String m)             { this.maturity       = m; }
    void setDataAsOf(Instant t)            { this.dataAsOf       = t; }
    void setDegraded(boolean d)            { this.degraded       = d; }
    void setPartialBucket(boolean p)       { this.partialBucket  = p; }
    void setIncompleteData(boolean i)      { this.incompleteData = i; }
    void incrementVersion()                { this.projectionVersion++; }

    KpiProjection toDto(long stalenessSeconds) {
        return new KpiProjection(id, metricKey, segmentKey, windowKey,
                value, numerator, denominator, sampleCount, maturity,
                dataAsOf, projectionVersion, degraded, stalenessSeconds);
    }
}
