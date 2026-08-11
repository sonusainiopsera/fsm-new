package com.fieldservice.fixtures;

import com.fieldservice.inventory.domain.LocationType;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.sla.domain.SlaPolicy;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

/**
 * Object-mother builders for {@link Part}, {@link StockBalance}, {@link StockLocation},
 * and {@link SlaPolicy}.
 *
 * <p>Part numbers use the reserved {@code PART-} namespace and are unique per builder call.
 * A zero-quantity balance and a missing balance are distinct: use {@link #zeroBalance} for
 * the former and simply omit a balance row for the latter.
 */
public final class InventoryFixtures {

    private InventoryFixtures() {}

    // ---- Part builder -------------------------------------------------------

    public static final class PartBuilder {

        private UUID   id          = DeterministicIds.next();
        private String partNumber;
        private String name;
        private String description;
        private String unitOfMeasure = "EACH";
        private int    reorderPoint  = 5;
        private int    reorderQty    = 10;

        private PartBuilder(String partNumber, String name) {
            this.partNumber = partNumber;
            this.name       = name;
        }

        public PartBuilder withId(UUID id)                  { this.id = id;                 return this; }
        public PartBuilder withDescription(String d)        { this.description = d;         return this; }
        public PartBuilder withUnitOfMeasure(String uom)    { this.unitOfMeasure = uom;     return this; }
        public PartBuilder withReorderPoint(int n)          { this.reorderPoint = n;        return this; }
        public PartBuilder withReorderQty(int n)            { this.reorderQty = n;          return this; }

        public Part build() {
            Part p = new Part(partNumber, name);
            setField(Part.class, p, "id", id);
            setField(Part.class, p, "description", description);
            setField(Part.class, p, "unitOfMeasure", unitOfMeasure);
            setField(Part.class, p, "reorderPoint", reorderPoint);
            setField(Part.class, p, "reorderQuantity", reorderQty);
            return p;
        }
    }

    // ---- StockLocation builder ----------------------------------------------

    public static final class StockLocationBuilder {

        private UUID         id           = DeterministicIds.next();
        private String       name;
        private LocationType locationType = LocationType.WAREHOUSE;
        private UUID         technicianId = null;
        private UUID         siteId       = null;

        private StockLocationBuilder(String name) {
            this.name = name;
        }

        public StockLocationBuilder withId(UUID id)                  { this.id = id;              return this; }
        public StockLocationBuilder withSiteId(UUID siteId)          { this.siteId = siteId;      return this; }

        /** VEHICLE location — requires a technicianId per the DB CHECK constraint. */
        public StockLocationBuilder asVehicle(UUID technicianId) {
            this.locationType = LocationType.VEHICLE;
            this.technicianId = technicianId;
            return this;
        }

        public StockLocation build() {
            StockLocation sl = new StockLocation(name, locationType);
            setField(StockLocation.class, sl, "id", id);
            setField(StockLocation.class, sl, "technicianId", technicianId);
            setField(StockLocation.class, sl, "siteId", siteId);
            return sl;
        }
    }

    // ---- StockBalance builder -----------------------------------------------

    public static final class StockBalanceBuilder {

        private UUID id         = DeterministicIds.next();
        private UUID partId;
        private UUID locationId;
        private int  quantityOnHand = 0;

        private StockBalanceBuilder(UUID partId, UUID locationId) {
            this.partId     = partId;
            this.locationId = locationId;
        }

        public StockBalanceBuilder withId(UUID id)       { this.id = id;              return this; }
        public StockBalanceBuilder withQuantity(int qty) { this.quantityOnHand = qty; return this; }

        /** Zero-quantity balance — distinct from a missing balance row. */
        public StockBalanceBuilder zeroQuantity() {
            this.quantityOnHand = 0;
            return this;
        }

        public StockBalance build() {
            StockBalance sb = new StockBalance(partId, locationId);
            setField(StockBalance.class, sb, "id", id);
            if (quantityOnHand != 0) {
                sb.adjustQuantity(quantityOnHand);
            }
            return sb;
        }
    }

    // ---- SlaPolicy builder --------------------------------------------------

    public static final class SlaPolicyBuilder {

        private UUID    id                = DeterministicIds.next();
        private String  priority;
        private int     responseMinutes;
        private int     resolutionMinutes;
        private Instant effectiveFrom     = DeterministicIds.FIXED_INSTANT;

        private SlaPolicyBuilder(String priority, int responseMinutes, int resolutionMinutes) {
            this.priority          = priority;
            this.responseMinutes   = responseMinutes;
            this.resolutionMinutes = resolutionMinutes;
        }

        public SlaPolicyBuilder withId(UUID id)                    { this.id = id;                   return this; }
        public SlaPolicyBuilder withEffectiveFrom(Instant instant) { this.effectiveFrom = instant;   return this; }

        public SlaPolicy build() {
            SlaPolicy sla = new SlaPolicy(priority, responseMinutes, resolutionMinutes, effectiveFrom);
            setField(SlaPolicy.class, sla, "id", id);
            return sla;
        }
    }

    // ---- Static factories ---------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger partCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static PartBuilder part(String partNumber, String name) {
        return new PartBuilder(partNumber, name);
    }

    public static PartBuilder nextPart() {
        int n = partCounter.incrementAndGet();
        return new PartBuilder("PART-" + String.format("%04d", n), "Fixture Part " + n);
    }

    public static StockLocationBuilder warehouse(String name) {
        return new StockLocationBuilder(name);
    }

    public static StockLocationBuilder vehicleStock(String name, UUID technicianId) {
        return new StockLocationBuilder(name).asVehicle(technicianId);
    }

    /** Zero-quantity balance — the part exists in the location but quantity is 0. */
    public static StockBalanceBuilder zeroBalance(UUID partId, UUID locationId) {
        return new StockBalanceBuilder(partId, locationId).zeroQuantity();
    }

    /** Positive-quantity balance. */
    public static StockBalanceBuilder positiveBalance(UUID partId, UUID locationId, int qty) {
        return new StockBalanceBuilder(partId, locationId).withQuantity(qty);
    }

    public static SlaPolicyBuilder slaLow() {
        return new SlaPolicyBuilder("LOW", 240, 480);
    }

    public static SlaPolicyBuilder slaMedium() {
        return new SlaPolicyBuilder("MEDIUM", 120, 240);
    }

    public static SlaPolicyBuilder slaHigh() {
        return new SlaPolicyBuilder("HIGH", 60, 120);
    }

    public static SlaPolicyBuilder slaCritical() {
        return new SlaPolicyBuilder("CRITICAL", 30, 60);
    }

    // ---- Reflection helper --------------------------------------------------

    static void setField(Class<?> cls, Object target, String name, Object value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Fixture reflection failed: " + cls.getSimpleName() + "." + name, e);
        }
    }
}
