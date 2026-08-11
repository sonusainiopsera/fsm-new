package com.fieldservice.domain.assignment;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a technician assignment to a work order.
 *
 * <p>Each row represents a single assignment event; the current assignment is the row
 * where {@code is_current = true}. When a technician is reassigned, the previous record
 * is marked {@code is_current = false} and a new record is inserted.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only assignments for their own {@code technicianId}.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — sees only assignments for work orders on their sites (join to work_order → site).</li>
 * </ul>
 */
@Entity
@Table(name = "assignment")
public class Assignment implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "unassigned_at")
    private Instant unassignedAt;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    protected Assignment() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkOrderId() {
        return workOrderId;
    }

    public void setWorkOrderId(UUID workOrderId) {
        this.workOrderId = workOrderId;
    }

    public UUID getTechnicianId() {
        return technicianId;
    }

    public void setTechnicianId(UUID technicianId) {
        this.technicianId = technicianId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getUnassignedAt() {
        return unassignedAt;
    }

    public void setUnassignedAt(Instant unassignedAt) {
        this.unassignedAt = unassignedAt;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }
}
