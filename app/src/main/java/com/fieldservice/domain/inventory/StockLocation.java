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
 * A named location where parts are held.
 *
 * <p>Location types:
 * <ul>
 *   <li>{@code WAREHOUSE} — fixed physical location; {@code technician_id} must be NULL.</li>
 *   <li>{@code VEHICLE} — mobile van/truck owned by a technician; {@code technician_id} must be set.</li>
 *   <li>{@code VAN} / {@code SITE} — legacy types retained for backward compatibility.</li>
 * </ul>
 *
 * <p>Envers-audited via {@code stock_location_aud}.
 */
@Entity
@Audited
@Table(name = "stock_location")
public class StockLocation {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "technician_id")
    private UUID technicianId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "location_type", nullable = false, length = 50)
    private String locationType = "WAREHOUSE";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockLocation() {
    }

    public UUID getId() { return id; }

    public UUID getTechnicianId() { return technicianId; }
    public void setTechnicianId(UUID technicianId) { this.technicianId = technicianId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getLocationType() { return locationType; }
    public void setLocationType(String locationType) { this.locationType = locationType; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
