package com.fieldservice.domain.workorder;

import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The central aggregate in the platform: a unit of field service work to be performed.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all; see all work orders.</li>
 *   <li>TECHNICIAN — sees only work orders where {@code assignedTechnicianId} equals
 *       their technician id. This scope is evaluated on every read; a technician
 *       reassigned off a work order loses access immediately on the next request with
 *       no cache invalidation step required.</li>
 *   <li>CUSTOMER — sees only work orders whose {@link Site#getCustomerAccountId()}
 *       is in the set of accounts linked to their principal.</li>
 * </ul>
 */
@Entity
@Table(name = "work_order")
public class WorkOrder implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false, insertable = false, updatable = false)
    private UUID siteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    /**
     * The ID of the technician currently assigned to this work order.
     * {@code null} when the work order is unassigned (state {@code OPEN}).
     * Updated whenever a dispatcher assigns or reassigns the work order.
     */
    @Column(name = "assigned_technician_id")
    private UUID assignedTechnicianId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    private WorkOrderState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private WorkOrderPriority priority;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected WorkOrder() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public Site getSite() {
        return site;
    }

    public void setSite(Site site) {
        this.site = site;
        this.siteId = site != null ? site.getId() : null;
    }

    public UUID getAssignedTechnicianId() {
        return assignedTechnicianId;
    }

    public void setAssignedTechnicianId(UUID assignedTechnicianId) {
        this.assignedTechnicianId = assignedTechnicianId;
    }

    public WorkOrderState getState() {
        return state;
    }

    public void setState(WorkOrderState state) {
        this.state = state;
    }

    public WorkOrderPriority getPriority() {
        return priority;
    }

    public void setPriority(WorkOrderPriority priority) {
        this.priority = priority;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }

    public void setSlaDeadline(Instant slaDeadline) {
        this.slaDeadline = slaDeadline;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
