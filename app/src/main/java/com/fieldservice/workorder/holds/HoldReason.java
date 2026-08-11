package com.fieldservice.workorder.holds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Reference entity for the controlled hold reason vocabulary.
 * Rows are never deleted; inactive codes remain so historical hold records resolve.
 */
@Immutable
@Entity
@Table(name = "hold_reason")
public class HoldReason {

    @Id
    @Column(length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected HoldReason() {}

    public String  getCode()      { return code; }
    public String  getLabel()     { return label; }
    public boolean isActive()     { return active; }
    public int     getSortOrder() { return sortOrder; }
}
