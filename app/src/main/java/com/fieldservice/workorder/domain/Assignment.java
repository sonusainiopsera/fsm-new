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

    protected Assignment() {}

    public Assignment(UUID workOrderId, UUID technicianId) {
        this.id           = UuidV7.generate();
        this.workOrderId  = workOrderId;
        this.technicianId = technicianId;
    }

    public UUID    getId()          { return id; }
    public UUID    getWorkOrderId() { return workOrderId; }
    public UUID    getTechnicianId(){ return technicianId; }
    public Instant getAssignedAt()  { return assignedAt; }
    public Instant getReleasedAt()  { return releasedAt; }
    public Instant getCreatedAt()   { return createdAt; }
    public Integer getVersion()     { return version; }
}
