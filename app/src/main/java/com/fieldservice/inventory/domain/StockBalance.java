package com.fieldservice.inventory.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

@Entity
@Table(name = "stock_balance")
public class StockBalance {

    @Id
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** CHECK (quantity_on_hand >= 0) enforced at the database level. */
    @Column(name = "quantity_on_hand", nullable = false)
    private Integer quantityOnHand = 0;

    @Version
    private Integer version;

    protected StockBalance() {}

    public StockBalance(UUID partId, UUID locationId) {
        this.id         = UuidV7.generate();
        this.partId     = partId;
        this.locationId = locationId;
    }

    public UUID    getId()             { return id; }
    public UUID    getPartId()         { return partId; }
    public UUID    getLocationId()     { return locationId; }
    public Integer getQuantityOnHand() { return quantityOnHand; }
    public Integer getVersion()        { return version; }

    public void adjustQuantity(int delta) {
        this.quantityOnHand += delta;
    }
}
