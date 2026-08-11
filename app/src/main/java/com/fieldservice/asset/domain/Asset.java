package com.fieldservice.asset.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset")
public class Asset {

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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

    public UUID    getId()           { return id; }
    public UUID    getSiteId()       { return siteId; }
    public String  getSerialNumber() { return serialNumber; }
    public String  getModel()        { return model; }
    public String  getManufacturer() { return manufacturer; }
    public Instant getInstalledAt()  { return installedAt; }
    public Instant getCreatedAt()    { return createdAt; }
    public Integer getVersion()      { return version; }
}
