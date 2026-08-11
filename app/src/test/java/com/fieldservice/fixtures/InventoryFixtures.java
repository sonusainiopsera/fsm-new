package com.fieldservice.fixtures;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockLocation;

import java.util.UUID;

/**
 * Object-mother for {@link Part}, {@link StockLocation}, and {@link StockBalance} fixtures.
 *
 * <p>Zero-quantity stock and a part with no stock row are distinct representable cases:
 * <ul>
 *   <li>{@link #zeroBalance(UUID, UUID)} — a balance row exists with {@code quantity_on_hand = 0}.</li>
 *   <li>Absence of a row for a (part, location) pair — caller simply omits calling any balance
 *       builder for that pair; no fixture row means no stock record exists.</li>
 * </ul>
 *
 * <p>{@code quantity_reserved} is always zero in these fixtures (WO-148: reservation semantics
 * are out of scope; no code path may set this field in the current release).
 */
public final class InventoryFixtures {

    private InventoryFixtures() {}

    // -----------------------------------------------------------------------
    // Part factories
    // -----------------------------------------------------------------------

    public static PartBuilder activePart() {
        return new PartBuilder()
                .withPartNumber("PN-FX-001")
                .withSku("SKU-FX-001")
                .withName("Fixture Part Active")
                .withDescription("Active fixture part for test use")
                .withUnitOfMeasure("EACH")
                .withActive(true);
    }

    public static PartBuilder inactivePart() {
        return new PartBuilder()
                .withPartNumber("PN-FX-002")
                .withSku("SKU-FX-002")
                .withName("Fixture Part Inactive")
                .withDescription("Inactive fixture part — should not appear in active catalogue")
                .withUnitOfMeasure("EACH")
                .withActive(false);
    }

    // -----------------------------------------------------------------------
    // StockLocation factories
    // -----------------------------------------------------------------------

    public static LocationBuilder warehouseLocation() {
        return new LocationBuilder()
                .withName("Fixture Warehouse")
                .withLocationType("WAREHOUSE")
                .withActive(true);
    }

    public static LocationBuilder vehicleLocation(UUID technicianId) {
        return new LocationBuilder()
                .withName("Fixture Van")
                .withLocationType("VEHICLE")
                .withTechnicianId(technicianId)
                .withActive(true);
    }

    // -----------------------------------------------------------------------
    // StockBalance factories
    // -----------------------------------------------------------------------

    /** A balance row with exactly zero on-hand quantity. Distinct from "no row exists". */
    public static StockBalance zeroBalance(UUID partId, UUID locationId) {
        return balanceBuilder(partId, locationId, 0).build();
    }

    /** A balance row with the specified positive on-hand quantity. */
    public static StockBalance positiveBalance(UUID partId, UUID locationId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Use zeroBalance() for quantity=0");
        return balanceBuilder(partId, locationId, quantity).build();
    }

    static BalanceBuilder balanceBuilder(UUID partId, UUID locationId, int qty) {
        return new BalanceBuilder().withPartId(partId).withLocationId(locationId).withQuantityOnHand(qty);
    }

    // -----------------------------------------------------------------------
    // Part builder
    // -----------------------------------------------------------------------

    public static final class PartBuilder {

        private String partNumber = "PN-FX-000";
        private String sku = "SKU-FX-000";
        private String name = "Fixture Part";
        private String description = "Fixture part description";
        private String unitOfMeasure = "EACH";
        private int reorderPoint = 2;
        private int reorderQuantity = 5;
        private boolean active = true;

        public PartBuilder withPartNumber(String pn)       { this.partNumber = pn;          return this; }
        public PartBuilder withSku(String sku)             { this.sku = sku;                return this; }
        public PartBuilder withName(String name)           { this.name = name;              return this; }
        public PartBuilder withDescription(String desc)    { this.description = desc;       return this; }
        public PartBuilder withUnitOfMeasure(String uom)   { this.unitOfMeasure = uom;      return this; }
        public PartBuilder withReorderPoint(int rp)        { this.reorderPoint = rp;        return this; }
        public PartBuilder withReorderQuantity(int rq)     { this.reorderQuantity = rq;     return this; }
        public PartBuilder withActive(boolean active)      { this.active = active;          return this; }

        public Part build() {
            Part part = new Part();
            // Part does not extend BaseEntity so ID is generated by Hibernate on persist.
            part.setPartNumber(partNumber);
            part.setSku(sku);
            part.setName(name);
            part.setDescription(description);
            part.setUnitOfMeasure(unitOfMeasure);
            part.setUnit(unitOfMeasure);
            part.setReorderPoint(reorderPoint);
            part.setReorderQuantity(reorderQuantity);
            part.setActive(active);
            return part;
        }
    }

    // -----------------------------------------------------------------------
    // StockLocation builder
    // -----------------------------------------------------------------------

    public static final class LocationBuilder {

        private UUID technicianId;
        private String name = "Fixture Location";
        private String locationType = "WAREHOUSE";
        private boolean active = true;

        public LocationBuilder withTechnicianId(UUID techId) { this.technicianId = techId;  return this; }
        public LocationBuilder withName(String name)          { this.name = name;            return this; }
        public LocationBuilder withLocationType(String type)  { this.locationType = type;   return this; }
        public LocationBuilder withActive(boolean active)     { this.active = active;       return this; }

        public StockLocation build() {
            StockLocation loc = new StockLocation();
            // StockLocation does not extend BaseEntity so ID is generated by Hibernate on persist.
            loc.setTechnicianId(technicianId);
            loc.setName(name);
            loc.setLocationType(locationType);
            loc.setActive(active);
            return loc;
        }
    }

    // -----------------------------------------------------------------------
    // StockBalance builder
    // -----------------------------------------------------------------------

    public static final class BalanceBuilder {

        private UUID partId;
        private UUID locationId;
        private int quantityOnHand = 0;

        public BalanceBuilder withPartId(UUID partId)          { this.partId = partId;         return this; }
        public BalanceBuilder withLocationId(UUID locationId)  { this.locationId = locationId; return this; }
        public BalanceBuilder withQuantityOnHand(int qty)      { this.quantityOnHand = qty;    return this; }

        public StockBalance build() {
            if (partId == null)    throw new IllegalStateException("partId required");
            if (locationId == null) throw new IllegalStateException("locationId required");
            StockBalance balance = new StockBalance();
            // StockBalance does not extend BaseEntity so ID is generated by Hibernate on persist.
            balance.setPartId(partId);
            balance.setLocationId(locationId);
            balance.setQuantityOnHand(quantityOnHand);
            return balance;
        }
    }
}
