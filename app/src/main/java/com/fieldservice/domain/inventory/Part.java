package com.fieldservice.domain.inventory;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Parts catalogue reference data.
 *
 * <p>Not a scoped entity: parts catalogue is read-only reference data accessible to
 * all authenticated non-CUSTOMER roles. Scope enforcement is not needed here.
 *
 * <p>Audited via {@code part_aud} (Envers). The {@code part_number} column is the
 * canonical business identifier; {@code sku} is the legacy column retained for
 * backward compatibility.
 */
@Entity
@Audited
@Table(name = "part")
public class Part {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "part_number", nullable = false, length = 100)
    private String partNumber;

    @Column(name = "description")
    private String description;

    @Column(name = "unit_of_measure", nullable = false, length = 50)
    private String unitOfMeasure = "EACH";

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint = 0;

    @Column(name = "reorder_quantity", nullable = false)
    private int reorderQuantity = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // Legacy columns retained for backward compatibility
    @Column(name = "sku", nullable = false, length = 100)
    private String sku;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "unit", nullable = false, length = 50)
    private String unit = "EACH";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Part() {
    }

    public UUID getId() { return id; }

    public String getPartNumber() { return partNumber; }
    public void setPartNumber(String partNumber) {
        this.partNumber = partNumber;
        // Keep legacy sku in sync for backward compat
        if (this.sku == null) this.sku = partNumber;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getUnitOfMeasure() { return unitOfMeasure; }
    public void setUnitOfMeasure(String unitOfMeasure) {
        this.unitOfMeasure = unitOfMeasure;
        this.unit = unitOfMeasure;
    }

    public int getReorderPoint() { return reorderPoint; }
    public void setReorderPoint(int reorderPoint) { this.reorderPoint = reorderPoint; }

    public int getReorderQuantity() { return reorderQuantity; }
    public void setReorderQuantity(int reorderQuantity) { this.reorderQuantity = reorderQuantity; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    // Legacy accessors
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
