package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Current quantity-on-hand per part per stock location.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only balances for locations owned by their technician id
 *       ({@code stock_location.technician_id = scope.technicianId()}).</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — deny-all (inventory data is not customer-facing).</li>
 * </ul>
 *
 * <p>The {@code quantity_on_hand >= 0} CHECK constraint (enforced at the DB level)
 * implements BR-16 (no negative inventory). The {@code version} column enables
 * optimistic locking on the conditional-decrement path.
 *
 * <p>Note: this table has no {@code created_at} column; use {@code updated_at} for
 * audit purposes. It does NOT extend {@link com.fieldservice.platform.entity.BaseEntity}.
 */
@Entity
@Table(name = "stock_balance")
public class StockBalance implements ScopedEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockBalance() {
    }

    public UUID getId() { return id; }

    public UUID getPartId() { return partId; }
    public void setPartId(UUID partId) { this.partId = partId; }

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public int getQuantityOnHand() { return quantityOnHand; }
    public void setQuantityOnHand(int quantityOnHand) { this.quantityOnHand = quantityOnHand; }

    public Integer getVersion() { return version; }

    public Instant getUpdatedAt() { return updatedAt; }
}
