package com.fieldservice.domain.inventory;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Current quantity-on-hand per part per stock location.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only balances for locations owned by their technician id.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — deny-all; inventory data is not customer-facing.</li>
 * </ul>
 *
 * <p>The {@code quantity_on_hand >= 0} CHECK constraint named {@code stock_non_negative}
 * implements BR-16. The {@code version} column enables optimistic locking on the
 * conditional-decrement path delivered in WO-053.
 *
 * <p>NOT Envers-audited: the append-only {@code stock_ledger} table (WO-054) is the
 * audit mechanism for balance changes (ADR-0012: stock-balance-audit-mechanism).
 *
 * <p>{@code quantity_reserved} column exists as structural pre-wiring for reservation
 * semantics; no code path in this release may set it (WO-148 constraint).
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

    /** Pre-wired for reservation semantics; not settable in this release (WO-148). */
    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public int getQuantityReserved() { return quantityReserved; }

    public Integer getVersion() { return version; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
