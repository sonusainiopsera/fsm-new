package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only ledger for inventory movements. No UPDATE or DELETE path from application. */
@Entity
@Table(name = "stock_ledger")
public class StockLedger {

    @Id
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "quantity_change", nullable = false)
    private Integer quantityChange;

    @Column(length = 100)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected StockLedger() {}

    public StockLedger(UUID partId, UUID locationId, int quantityChange, String reference) {
        this.id             = UuidV7.generate();
        this.partId         = partId;
        this.locationId     = locationId;
        this.quantityChange = quantityChange;
        this.reference      = reference;
    }

    public UUID    getId()             { return id; }
    public UUID    getPartId()         { return partId; }
    public UUID    getLocationId()     { return locationId; }
    public Integer getQuantityChange() { return quantityChange; }
    public String  getReference()      { return reference; }
    public Instant getCreatedAt()      { return createdAt; }
}
