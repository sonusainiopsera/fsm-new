package com.fieldservice.domain.assignment;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "assignments")
public class Assignment implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    @Column(name = "technician_id", nullable = false)
    private String technicianId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected Assignment() {}

    public Assignment(WorkOrder workOrder, String technicianId) {
        this.workOrder = workOrder;
        this.technicianId = technicianId;
        this.assignedAt = Instant.now();
        this.active = true;
    }

    public UUID getId() { return id; }
    public WorkOrder getWorkOrder() { return workOrder; }
    public String getTechnicianId() { return technicianId; }
    public Instant getAssignedAt() { return assignedAt; }
    public boolean isActive() { return active; }
    public void deactivate() { this.active = false; }
}
