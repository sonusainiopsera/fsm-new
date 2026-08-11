package com.fieldservice.asset.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * A piece of equipment installed at a site, serviced via work orders.
 *
 * <p>Row-scoped: a CUSTOMER principal may only see assets belonging to their sites.
 * The {@code siteId} FK is used by the scope predicate via a subquery to the site table.
 */
@Audited
@Entity
@Table(name = "asset")
public class Asset implements ScopedEntity {

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(length = 255)
    private String model;

    @Column(length = 255)
    private String manufacturer;

    @Column(name = "installed_at")
    private Instant installedAt;

    @Column(name = "asset_tag", length = 100)
    private String assetTag;

    @Column(length = 100)
    private String category;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private Integer version;

    protected Asset() {}

    public Asset(UUID siteId, String serialNumber, String model, String manufacturer) {
        this.id           = UuidV7.generate();
        this.siteId       = siteId;
        this.serialNumber = serialNumber;
        this.model        = model;
        this.manufacturer = manufacturer;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void deactivate(Instant now) {
        this.active        = false;
        this.deactivatedAt = now;
        this.updatedAt     = now;
    }

    public void update(String assetTag, String manufacturer, String model,
                       String serialNumber, String category, Instant installedAt, UUID actorId) {
        this.assetTag     = assetTag;
        this.manufacturer = manufacturer;
        this.model        = model;
        this.serialNumber = serialNumber;
        this.category     = category;
        this.installedAt  = installedAt;
        this.updatedBy    = actorId;
    }

    public UUID    getId()           { return id; }
    public UUID    getSiteId()       { return siteId; }
    public String  getSerialNumber() { return serialNumber; }
    public String  getModel()        { return model; }
    public String  getManufacturer() { return manufacturer; }
    public Instant getInstalledAt()  { return installedAt; }
    public String  getAssetTag()     { return assetTag; }
    public String  getCategory()     { return category; }
    public boolean isActive()        { return Boolean.TRUE.equals(active); }
    public Instant getDeactivatedAt(){ return deactivatedAt; }
    public Instant getCreatedAt()    { return createdAt; }
    public UUID    getCreatedBy()    { return createdBy; }
    public Instant getUpdatedAt()    { return updatedAt; }
    public UUID    getUpdatedBy()    { return updatedBy; }
    public Integer getVersion()      { return version; }
}
