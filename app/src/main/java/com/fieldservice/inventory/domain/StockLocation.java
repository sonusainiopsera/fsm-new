package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_location")
public class StockLocation {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected StockLocation() {}

    public StockLocation(String name) {
        this.id   = UuidV7.generate();
        this.name = name;
    }

    public UUID    getId()        { return id; }
    public String  getName()      { return name; }
    public UUID    getSiteId()    { return siteId; }
    public Instant getCreatedAt() { return createdAt; }
}
