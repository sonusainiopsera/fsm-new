package com.fieldservice.domain.assignment;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

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
 *   <li>CUSTOMER — deny-all (assignments are internal operational data,
 *       not exposed in the customer portal).</li>
 * </ul>
 *
 * <p>Note: this table uses {@code assigned_at} (not {@code created_at}) and has no
 * {@code updated_at}, so it does not extend {@link com.fieldservice.platform.entity.BaseEntity}.
 * The version column is present for optimistic locking on the is_current flag update.
 */
@Audited
@Entity
@Table(name = "assignment")
public class Assignment implements ScopedEntity {

    @Id
    @GeneratedUuidV7
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

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

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

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Integer getVersion() {
        return version;
    }
}
