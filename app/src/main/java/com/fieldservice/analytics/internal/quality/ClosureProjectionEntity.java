package com.fieldservice.analytics.internal.quality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code analytics_closure_projection} table.
 *
 * <p>Package-private — only accessible within the quality sub-package.
 * Callers in the parent package interact via {@link RepeatVisitLinker} and
 * the {@link FirstTimeFixCalculator} aggregator.
 */
@Entity
@Table(name = "analytics_closure_projection")
class ClosureProjectionEntity {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "asset_category", length = 100)
    private String assetCategory;

    @Column(name = "fault_key", length = 200)
    private String faultKey;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "maturity", nullable = false, length = 20)
    private String maturity;

    @Column(name = "matured_at", nullable = false)
    private Instant maturedAt;

    @Column(name = "is_first_time_fix", nullable = false)
    private boolean firstTimeFix = true;

    protected ClosureProjectionEntity() {}

    ClosureProjectionEntity(UUID id, UUID workOrderId, UUID assetId, String assetCategory,
                             String faultKey, Instant closedAt, String maturity, Instant maturedAt) {
        this.id            = id;
        this.workOrderId   = workOrderId;
        this.assetId       = assetId;
        this.assetCategory = assetCategory;
        this.faultKey      = faultKey;
        this.closedAt      = closedAt;
        this.maturity      = maturity;
        this.maturedAt     = maturedAt;
        this.firstTimeFix  = true;
    }

    UUID    getId()             { return id; }
    UUID    getWorkOrderId()    { return workOrderId; }
    UUID    getAssetId()        { return assetId; }
    String  getAssetCategory()  { return assetCategory; }
    String  getFaultKey()       { return faultKey; }
    Instant getClosedAt()       { return closedAt; }
    String  getMaturity()       { return maturity; }
    Instant getMaturedAt()      { return maturedAt; }
    boolean isFirstTimeFix()    { return firstTimeFix; }

    void setMaturity(String maturity)          { this.maturity    = maturity; }
    void setFirstTimeFix(boolean firstTimeFix) { this.firstTimeFix = firstTimeFix; }
}
