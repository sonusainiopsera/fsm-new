package com.fieldservice.dispatch.snapshot;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable header row for one recommendation generation event.
 *
 * <p>Insert-only: no UPDATE or DELETE path exists anywhere in the application for this entity.
 * The row itself is the audit record referenced by assignment audit trails.
 */
@Entity
@Table(name = "recommendation_snapshot")
public class RecommendationSnapshot extends BaseEntity {

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "generated_by")
    private UUID generatedBy;

    @Column(name = "weight_set_version")
    private String weightSetVersion;

    @Column(name = "travel_estimate_degraded", nullable = false)
    private boolean travelEstimateDegraded;

    @Column(name = "parts_data_degraded", nullable = false)
    private boolean partsDataDegraded;

    @Column(name = "candidate_pool_size", nullable = false)
    private int candidatePoolSize;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    protected RecommendationSnapshot() {}

    public RecommendationSnapshot(UUID workOrderId, Instant generatedAt, UUID generatedBy,
                                  String weightSetVersion, boolean travelEstimateDegraded,
                                  boolean partsDataDegraded, int candidatePoolSize, boolean truncated) {
        this.workOrderId = workOrderId;
        this.generatedAt = generatedAt;
        this.generatedBy = generatedBy;
        this.weightSetVersion = weightSetVersion;
        this.travelEstimateDegraded = travelEstimateDegraded;
        this.partsDataDegraded = partsDataDegraded;
        this.candidatePoolSize = candidatePoolSize;
        this.truncated = truncated;
    }

    public UUID getWorkOrderId() { return workOrderId; }
    public Instant getGeneratedAt() { return generatedAt; }
    public UUID getGeneratedBy() { return generatedBy; }
    public String getWeightSetVersion() { return weightSetVersion; }
    public boolean isTravelEstimateDegraded() { return travelEstimateDegraded; }
    public boolean isPartsDataDegraded() { return partsDataDegraded; }
    public int getCandidatePoolSize() { return candidatePoolSize; }
    public boolean isTruncated() { return truncated; }
}
