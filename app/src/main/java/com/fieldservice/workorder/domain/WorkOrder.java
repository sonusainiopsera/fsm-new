package com.fieldservice.workorder.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import com.fieldservice.site.domain.Site;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Core aggregate root representing a field service job.
 *
 * <p>Row-scoped: the scope predicate varies by role:
 * <ul>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all (no row restriction)</li>
 *   <li>TECHNICIAN — {@code assigned_technician_id = :technicianId}</li>
 *   <li>CUSTOMER   — {@code site.customer_id IN :customerAccountIds}</li>
 * </ul>
 *
 * <p>The {@code site} association is LAZY and used only in the CUSTOMER scope predicate
 * join (via JPA Criteria API) — callers must not rely on eager loading.
 *
 * <p>{@code assignedTechnicianId} is stored as a plain UUID FK column (not a
 * {@code @ManyToOne}) to keep the TECHNICIAN scope predicate a simple equality check
 * without an additional join.
 */
@DataClassification(value = ClassificationTier.INTERNAL, module = "workorder")
@Audited
@Entity
@Table(name = "work_order")
public class WorkOrder implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 50, unique = true)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    private WorkOrderStatus state;

    @Column(nullable = false, length = 20)
    private String priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    /** Nullable — null means the work order is unassigned. */
    @Column(name = "assigned_technician_id")
    private UUID assignedTechnicianId;

    @Column(length = 4000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "cumulative_hold_minutes", nullable = false)
    private int cumulativeHoldMinutes = 0;

    @Column(name = "response_deadline")
    private Instant responseDeadline;

    @Column(name = "resolution_deadline")
    private Instant resolutionDeadline;

    @Column(name = "at_risk", nullable = false)
    private boolean atRisk = false;

    @Column(name = "at_risk_at")
    private Instant atRiskAt;

    @Column(name = "no_parts_required", nullable = false)
    private boolean noPartsRequired = false;

    @Version
    private Integer version;

    protected WorkOrder() {}

    public WorkOrder(String reference, WorkOrderStatus state, String priority,
                     Site site, UUID assignedTechnicianId) {
        this.id                  = UuidV7.generate();
        this.reference           = reference;
        this.state               = state;
        this.priority            = priority;
        this.site                = site;
        this.assignedTechnicianId = assignedTechnicianId;
    }

    /** For tests that need a deterministic id. */
    public WorkOrder(UUID id, String reference, WorkOrderStatus state, String priority,
                     Site site, UUID assignedTechnicianId) {
        this.id                  = id;
        this.reference           = reference;
        this.state               = state;
        this.priority            = priority;
        this.site                = site;
        this.assignedTechnicianId = assignedTechnicianId;
    }

    public UUID            getId()                  { return id; }
    public String          getReference()           { return reference; }
    public WorkOrderStatus getState()               { return state; }
    public String          getPriority()            { return priority; }
    public Site            getSite()                { return site; }
    public UUID            getAssignedTechnicianId() { return assignedTechnicianId; }
    public String          getDescription()         { return description; }
    public Instant         getCreatedAt()            { return createdAt; }
    public int             getCumulativeHoldMinutes(){ return cumulativeHoldMinutes; }
    public Instant         getResponseDeadline()    { return responseDeadline; }
    public Instant         getResolutionDeadline()  { return resolutionDeadline; }
    public boolean         isAtRisk()               { return atRisk; }
    public Instant         getAtRiskAt()            { return atRiskAt; }
    public boolean         isNoPartsRequired()      { return noPartsRequired; }
    public Integer         getVersion()             { return version; }

    public void markNoPartsRequired() { this.noPartsRequired = true; }

    public void setDescription(String description) { this.description = description; }

    /** Sets SLA deadlines when a work order is created from a priority policy. */
    public void applyDeadlines(Instant responseDeadline, Instant resolutionDeadline, Instant atRiskAt) {
        this.responseDeadline   = responseDeadline;
        this.resolutionDeadline = resolutionDeadline;
        this.atRiskAt           = atRiskAt;
    }

    /** Marks the work order at-risk when elapsed time exceeds the SLA at-risk threshold. */
    public void markAtRisk(boolean atRisk) {
        this.atRisk = atRisk;
    }

    /** Assigns a technician to this work order. */
    public void assignTechnician(UUID technicianId) {
        this.assignedTechnicianId = technicianId;
        this.state = WorkOrderStatus.ASSIGNED;
    }

    /** Removes the current technician assignment (back to NEW). */
    public void unassign() {
        this.assignedTechnicianId = null;
        this.state = WorkOrderStatus.NEW;
    }

    /**
     * Applies a validated state transition. Called exclusively by
     * {@code WorkOrderTransitionService}; no other caller should mutate state directly.
     */
    public void applyStateTransition(WorkOrderStatus newState) {
        this.state = newState;
    }

    /** Adds elapsed hold minutes at resume time. Negative values are ignored (clock skew guard). */
    public void incrementCumulativeHoldMinutes(int minutes) {
        if (minutes > 0) {
            this.cumulativeHoldMinutes += minutes;
        }
    }
}
