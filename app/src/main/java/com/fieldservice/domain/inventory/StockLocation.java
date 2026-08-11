package com.fieldservice.domain.inventory;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A named location where parts are held — either a technician's van or a warehouse shelf.
 *
 * <p>Not a scoped entity: location management is restricted to privileged roles at the
 * service layer. Technicians access their own van stock via {@link StockBalance}.
 *
 * <p>Note: the {@code stock_location} table has no {@code version} or {@code updated_at}
 * columns because locations are append-only by convention.
 */
@Entity
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
    private String locationType = "VAN";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
}
