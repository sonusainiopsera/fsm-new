package com.fieldservice.dispatch.snapshot;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One ranked candidate row within a {@link RecommendationSnapshot}.
 *
 * <p>Insert-only. The {@code factor_breakdown} column stores the full JSON representation
 * of the scoring breakdown as returned by the scoring engine at generation time.
 */
@Entity
@Table(name = "recommendation_snapshot_candidate")
public class RecommendationSnapshotCandidate extends BaseEntity {

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "score", nullable = false, precision = 7, scale = 6)
    private double score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factor_breakdown", nullable = false, columnDefinition = "jsonb")
    private String factorBreakdown;

    protected RecommendationSnapshotCandidate() {}

    public RecommendationSnapshotCandidate(UUID snapshotId, UUID technicianId,
                                           int rank, double score, String factorBreakdown) {
        this.snapshotId = snapshotId;
        this.technicianId = technicianId;
        this.rank = rank;
        this.score = score;
        this.factorBreakdown = factorBreakdown;
    }

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getTechnicianId() { return technicianId; }
    public int getRank() { return rank; }
    public double getScore() { return score; }
    public String getFactorBreakdown() { return factorBreakdown; }
}
