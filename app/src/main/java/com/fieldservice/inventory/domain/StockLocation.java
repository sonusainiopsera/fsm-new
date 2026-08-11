package com.fieldservice.inventory.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "stock_location")
public class StockLocation implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 20)
    private LocationType locationType = LocationType.WAREHOUSE;

    @Column(name = "technician_id")
    private UUID technicianId;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected StockLocation() {}

    public StockLocation(String name, LocationType locationType) {
        this.id           = UuidV7.generate();
        this.name         = name;
        this.locationType = locationType;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID         getId()           { return id; }
    public String       getName()         { return name; }
    public LocationType getLocationType() { return locationType; }
    public UUID         getTechnicianId() { return technicianId; }
    public UUID         getSiteId()       { return siteId; }
    public Instant      getCreatedAt()    { return createdAt; }
    public Instant      getUpdatedAt()    { return updatedAt; }
}
