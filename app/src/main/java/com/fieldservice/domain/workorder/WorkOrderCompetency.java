package com.fieldservice.domain.workorder;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A required competency (certification type) for a work order.
 *
 * <p>The CertificationCurrencyGuard loads all rows for the work order being assigned
 * and verifies that the target technician holds a current, unexpired certification
 * of each required {@code competencyCode}.
 *
 * <p>A work order with no competency rows passes the guard without checking certifications.
 */
@Entity
@Table(name = "work_order_competency")
public class WorkOrderCompetency {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "work_order_id", nullable = false)
    private UUID workOrderId;

    @Column(name = "competency_code", nullable = false, length = 100)
    private String competencyCode;

    protected WorkOrderCompetency() {
    }

    public UUID getId() { return id; }

    public UUID getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(UUID workOrderId) { this.workOrderId = workOrderId; }

    public String getCompetencyCode() { return competencyCode; }
    public void setCompetencyCode(String competencyCode) { this.competencyCode = competencyCode; }
}
