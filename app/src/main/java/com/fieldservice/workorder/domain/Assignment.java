package com.fieldservice.workorder.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

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
}
