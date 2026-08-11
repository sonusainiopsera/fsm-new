package com.fieldservice.domain.workorder;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Records labour time logged against a work order (BR-08).
 *
 * <p>The LabourTimeRecordedGuard checks for at least one row before permitting COMPLETE.
 * A work order with all labour records deleted (voided) will again fail the guard.
 */
@Entity
@Table(name = "labour_time_record")
public class LabourTimeRecord {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "technician_id", nullable = false)
    private UUID technicianId;

    @Column(name = "minutes", nullable = false)
    private int minutes;

    @Column(name = "work_date", nullable = false)
    private Instant workDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LabourTimeRecord() {
    }

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public UUID getTechnicianId() { return technicianId; }
    public void setTechnicianId(UUID technicianId) { this.technicianId = technicianId; }

    public int getMinutes() { return minutes; }
    public void setMinutes(int minutes) { this.minutes = minutes; }

    public Instant getWorkDate() { return workDate; }
    public void setWorkDate(Instant workDate) { this.workDate = workDate; }

    public Instant getCreatedAt() { return createdAt; }
}
