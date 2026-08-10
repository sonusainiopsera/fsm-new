package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "stock_balance")
public class StockBalance implements ScopedEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "part_id", nullable = false)
    private Part part;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private StockLocation location;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Version
    private Long version;

    protected StockBalance() {}

    public StockBalance(Part part, StockLocation location, int initialQuantity) {
        this.id = UuidV7.generate();
        this.part = part;
        this.location = location;
        this.quantityOnHand = initialQuantity;
    }

    public UUID getId() { return id; }
    public Part getPart() { return part; }
    public StockLocation getLocation() { return location; }
    public int getQuantityOnHand() { return quantityOnHand; }

    public void decrement(int qty) {
        if (quantityOnHand < qty) {
            throw new IllegalStateException("Insufficient stock: available=" + quantityOnHand + " requested=" + qty);
        }
        this.quantityOnHand -= qty;
    }

    public void increment(int qty) {
        this.quantityOnHand += qty;
    }
}
