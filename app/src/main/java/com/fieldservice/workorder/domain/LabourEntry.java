package com.fieldservice.workorder.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_order_labour_entry")
public class LabourEntry {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "technician_id", updatable = false)
    private UUID technicianId;

    @Column(nullable = false)
    private int minutes;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LabourEntry() {}

    public LabourEntry(UUID workOrderId, UUID technicianId, int minutes, String notes) {
        this.id           = UuidV7.generate();
        this.workOrderId  = workOrderId;
        this.technicianId = technicianId;
        this.minutes      = minutes;
        this.notes        = notes;
    }

    public UUID    getId()          { return id; }
    public UUID    getWorkOrderId() { return workOrderId; }
    public UUID    getTechnicianId(){ return technicianId; }
    public int     getMinutes()     { return minutes; }
    public String  getNotes()       { return notes; }
    public Instant getCreatedAt()   { return createdAt; }
}
