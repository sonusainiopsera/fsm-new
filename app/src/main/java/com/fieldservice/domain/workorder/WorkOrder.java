package com.fieldservice.domain.workorder;

import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

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
 *   <li>CUSTOMER — sees only work orders whose {@code customerId} is in the set of
 *       customer account IDs linked to their principal.</li>
 * </ul>
 */
@DataClassification(tier = ClassificationTier.INTERNAL, note = "Work order operational record — no personal data in body fields")
@Audited
@Entity
@Table(name = "work_order")
public class WorkOrder extends BaseEntity implements ScopedEntity {

    @Column(name = "asset_id")
    private UUID assetId;

    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }

    // UUID FK field is audited — stores site_id in work_order_aud
    @Column(name = "site_id", nullable = false, insertable = false, updatable = false)
    private UUID siteId;

    // @ManyToOne excluded — UUID field above handles the FK column in the audit table
    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    // UUID FK field is audited — stores customer_id in work_order_aud
    @Column(name = "customer_id", nullable = false, insertable = false, updatable = false)
    private UUID customerId;

    // @ManyToOne excluded — UUID field above handles the FK column in the audit table
    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /**
     * The ID of the technician currently assigned to this work order.
     * {@code null} when the work order is unassigned (state {@code NEW}).
     * Updated whenever a dispatcher assigns or reassigns the work order.
     */
    @Column(name = "assigned_technician_id")
    private UUID assignedTechnicianId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private WorkOrderState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private WorkOrderPriority priority;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Column(name = "response_due_at")
    private Instant responseDueAt;

    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Column(name = "at_risk_at")
    private Instant atRiskAt;

    @Column(name = "cumulative_hold_minutes", nullable = false)
    private int cumulativeHoldMinutes = 0;

    @DataClassification(tier = ClassificationTier.CONFIDENTIAL,
            note = "Free-text fault description may contain customer-reported PII — stored parameterised only, never logged in full")
    @Column(name = "fault_description", columnDefinition = "TEXT")
    private String faultDescription;

    @Column(name = "reference", length = 20)
    private String reference;

    @Column(name = "applied_sla_policy_id")
    private UUID appliedSlaPolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    private WorkOrderOrigin origin = WorkOrderOrigin.DISPATCHER;

    protected WorkOrder() {
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

    public UUID getCustomerId() {
        return customerId;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
        this.customerId = customer != null ? customer.getId() : null;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public int getCumulativeHoldMinutes() {
        return cumulativeHoldMinutes;
    }

    public void addHoldMinutes(int minutes) {
        if (minutes > 0) {
            this.cumulativeHoldMinutes += minutes;
        }
    }

    public Instant getResponseDueAt() { return responseDueAt; }
    public void setResponseDueAt(Instant responseDueAt) { this.responseDueAt = responseDueAt; }

    public Instant getResolutionDueAt() { return resolutionDueAt; }
    public void setResolutionDueAt(Instant resolutionDueAt) { this.resolutionDueAt = resolutionDueAt; }

    public Instant getAtRiskAt() { return atRiskAt; }
    public void setAtRiskAt(Instant atRiskAt) { this.atRiskAt = atRiskAt; }

    @Column(name = "no_parts_required", nullable = false)
    private boolean noPartsRequired = false;

    public boolean isNoPartsRequired() { return noPartsRequired; }
    public void setNoPartsRequired(boolean noPartsRequired) { this.noPartsRequired = noPartsRequired; }

    public String getFaultDescription() { return faultDescription; }
    public void setFaultDescription(String faultDescription) { this.faultDescription = faultDescription; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public UUID getAppliedSlaPolicyId() { return appliedSlaPolicyId; }
    public void setAppliedSlaPolicyId(UUID appliedSlaPolicyId) { this.appliedSlaPolicyId = appliedSlaPolicyId; }

    public WorkOrderOrigin getOrigin() { return origin; }
    public void setOrigin(WorkOrderOrigin origin) { this.origin = origin; }
}
