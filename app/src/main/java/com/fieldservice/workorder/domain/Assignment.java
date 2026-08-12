package com.fieldservice.workorder.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "assignment")
public class Assignment {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    @Column(name = "parts_warning_code", length = 50)
    private String partsWarningCode;

    @Column(name = "parts_shortfall_json", columnDefinition = "TEXT")
    private String partsShortfallJson;

    @Column(name = "acknowledge_warnings")
    private Boolean acknowledgeWarnings;

    @Column(name = "warning_acknowledgement_reason", length = 500)
    private String warningAcknowledgementReason;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "recommendation_snapshot_id")
    private UUID recommendationSnapshotId;

    @Column(name = "recommendation_rank")
    private Integer recommendationRank;

    @Column(name = "recommendation_score", precision = 7, scale = 6)
    private BigDecimal recommendationScore;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "snapshot_stale", nullable = false)
    private boolean snapshotStale = false;

    protected Assignment() {}

    public Assignment(UUID workOrderId, UUID technicianId) {
        this.id           = UuidV7.generate();
        this.workOrderId  = workOrderId;
        this.technicianId = technicianId;
    }

    public void applyWarning(String warningCode, String shortfallJson,
                              Boolean ackWarnings, String ackReason) {
        this.partsWarningCode             = warningCode;
        this.partsShortfallJson           = shortfallJson;
        this.acknowledgeWarnings          = ackWarnings;
        this.warningAcknowledgementReason = ackReason;
    }

    public UUID    getId()                          { return id; }
    public UUID    getWorkOrderId()                 { return workOrderId; }
    public UUID    getTechnicianId()                { return technicianId; }
    public Instant getAssignedAt()                  { return assignedAt; }
    public Instant getReleasedAt()                  { return releasedAt; }
    public Instant getCreatedAt()                   { return createdAt; }
    public Integer getVersion()                     { return version; }
    public String  getPartsWarningCode()            { return partsWarningCode; }
    public Boolean getAcknowledgeWarnings()         { return acknowledgeWarnings; }
    public String  getWarningAcknowledgementReason(){ return warningAcknowledgementReason; }

    public UUID       getAssignedBy()                  { return assignedBy; }
    public UUID       getRecommendationSnapshotId()    { return recommendationSnapshotId; }
    public Integer    getRecommendationRank()          { return recommendationRank; }
    public BigDecimal getRecommendationScore()         { return recommendationScore; }
    public String     getOverrideReason()              { return overrideReason; }
    public boolean    isSnapshotStale()                { return snapshotStale; }

    public void setAssignedBy(UUID assignedBy)                               { this.assignedBy = assignedBy; }
    public void setRecommendationSnapshotId(UUID recommendationSnapshotId)   { this.recommendationSnapshotId = recommendationSnapshotId; }
    public void setRecommendationRank(Integer recommendationRank)            { this.recommendationRank = recommendationRank; }
    public void setRecommendationScore(BigDecimal recommendationScore)       { this.recommendationScore = recommendationScore; }
    public void setOverrideReason(String overrideReason)                     { this.overrideReason = overrideReason; }
    public void setSnapshotStale(boolean snapshotStale)                      { this.snapshotStale = snapshotStale; }
}
