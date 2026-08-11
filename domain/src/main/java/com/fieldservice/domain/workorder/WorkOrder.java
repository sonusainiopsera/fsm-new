package com.fieldservice.domain.workorder;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_order")
@Audited
public class WorkOrder implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkOrderState state;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    /**
     * The currently assigned technician's identifier.
     * Nullable when unassigned. Changes immediately on reassignment —
     * the scope predicate checks this column so reassignment takes
     * effect on the very next request with no cache invalidation.
     */
    @Column(name = "assigned_technician_id")
    private String assignedTechnicianId;

    @Column(name = "priority", length = 20)
    private String priority;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected WorkOrder() {}

    public WorkOrder(String title, Site site, String priority) {
        this.id = UuidV7.generate();
        this.title = title;
        this.site = site;
        this.priority = priority;
        this.state = WorkOrderState.NEW;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public WorkOrderState getState() { return state; }
    public Site getSite() { return site; }
    public String getAssignedTechnicianId() { return assignedTechnicianId; }
    public String getPriority() { return priority; }
    public Instant getCreatedAt() { return createdAt; }

    public void assign(String technicianId) {
        this.assignedTechnicianId = technicianId;
        this.state = WorkOrderState.ASSIGNED;
    }

    public void unassign() {
        this.assignedTechnicianId = null;
        this.state = WorkOrderState.NEW;
    }

    public void setState(WorkOrderState state) { this.state = state; }
    public void setTitle(String title) { this.title = title; }
}
