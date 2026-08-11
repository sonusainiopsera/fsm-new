package com.fieldservice.domain.workorder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Controlled vocabulary entry for work-order holds (BR-09).
 *
 * <p>Code is the natural PK so FK references in {@link WorkOrderHold} remain human-readable.
 * Inactivating a code ({@code active=false}) prevents new holds from using it but does not
 * invalidate historical hold records that reference it.
 */
@Entity
@Table(name = "hold_reason")
public class HoldReason {

    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 100)
    private String code;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "pauses_sla_clock", nullable = false)
    private boolean pausesSLAClock;

    protected HoldReason() {
    }

    public String getCode() { return code; }

    public String getLabel() { return label; }

    public boolean isActive() { return active; }

    public int getSortOrder() { return sortOrder; }

    public boolean isPausesSLAClock() { return pausesSLAClock; }
}
