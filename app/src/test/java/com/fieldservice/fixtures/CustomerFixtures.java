package com.fieldservice.fixtures;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.customer.Customer;
import com.fieldservice.domain.site.Site;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Object-mother for {@link Customer}, {@link Site}, and {@link Asset} test fixtures.
 *
 * <p>All contact details use reserved domains ({@code example.com}) and reserved phone
 * ranges ({@code +15555550xxx}). Coordinates are within the synthetic bounding box
 * 34.00–34.10°N, 118.00–118.10°W (fictional non-residential area).
 */
public final class CustomerFixtures {

    private CustomerFixtures() {}

    // -----------------------------------------------------------------------
    // Customer factories
    // -----------------------------------------------------------------------

    public static CustomerBuilder customer() {
        return new CustomerBuilder()
                .withName("Fixture Corp A")
                .withContactEmail("billing@example.com")
                .withContactPhone("+15555550100")
                .withBillingAddress("100 Example Street, Springfield, EX 00001")
                .withActive(true);
    }

    public static CustomerBuilder inactiveCustomer() {
        return new CustomerBuilder()
                .withName("Fixture Corp Inactive")
                .withContactEmail("billing@example.org")
                .withContactPhone("+15555550101")
                .withBillingAddress("101 Example Avenue, Springfield, EX 00001")
                .withActive(false);
    }

    // -----------------------------------------------------------------------
    // Site factories
    // -----------------------------------------------------------------------

    public static SiteBuilder site(Customer customer) {
        return new SiteBuilder(customer)
                .withName("Fixture Site Alpha")
                .withAddress("1 Alpha Boulevard, Springfield, EX 00001")
                .withLatitude(new BigDecimal("34.0001"))
                .withLongitude(new BigDecimal("-118.0001"))
                .withActive(true);
    }

    public static SiteBuilder secondSite(Customer customer) {
        return new SiteBuilder(customer)
                .withName("Fixture Site Beta")
                .withAddress("2 Beta Boulevard, Springfield, EX 00001")
                .withLatitude(new BigDecimal("34.0052"))
                .withLongitude(new BigDecimal("-118.0052"))
                .withActive(true);
    }

    // -----------------------------------------------------------------------
    // Asset factories
    // -----------------------------------------------------------------------

    public static AssetBuilder hvacAsset(Site site) {
        return new AssetBuilder(site)
                .withName("HVAC Unit Alpha-1")
                .withAssetType("HVAC")
                .withSerialNo("SN-HVAC-FX-001");
    }

    public static AssetBuilder boilerAsset(Site site) {
        return new AssetBuilder(site)
                .withName("Boiler Beta-1")
                .withAssetType("BOILER")
                .withSerialNo("SN-BOIL-FX-001");
    }

    // -----------------------------------------------------------------------
    // Customer builder
    // -----------------------------------------------------------------------

    public static final class CustomerBuilder {

        private UUID id = DeterministicIds.nextId();
        private String name;
        private String contactEmail;
        private String contactPhone;
        private String billingAddress;
        private boolean active = true;

        public CustomerBuilder withId(UUID id)                    { this.id = id;                   return this; }
        public CustomerBuilder withName(String name)              { this.name = name;               return this; }
        public CustomerBuilder withContactEmail(String email)     { this.contactEmail = email;      return this; }
        public CustomerBuilder withContactPhone(String phone)     { this.contactPhone = phone;      return this; }
        public CustomerBuilder withBillingAddress(String address) { this.billingAddress = address;  return this; }
        public CustomerBuilder withActive(boolean active)         { this.active = active;           return this; }

        public Customer build() {
            Customer c = new Customer();
            c.setId(id);
            c.setName(name);
            c.setContactEmail(contactEmail);
            c.setContactPhone(contactPhone);
            c.setBillingAddress(billingAddress);
            c.setActive(active);
            return c;
        }
    }

    // -----------------------------------------------------------------------
    // Site builder
    // -----------------------------------------------------------------------

    public static final class SiteBuilder {

        private final Customer customer;
        private UUID id = DeterministicIds.nextId();
        private String name;
        private String address;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private boolean active = true;

        private SiteBuilder(Customer customer) {
            this.customer = customer;
        }

        public SiteBuilder withId(UUID id)                 { this.id = id;              return this; }
        public SiteBuilder withName(String name)           { this.name = name;          return this; }
        public SiteBuilder withAddress(String address)     { this.address = address;    return this; }
        public SiteBuilder withLatitude(BigDecimal lat)    { this.latitude = lat;       return this; }
        public SiteBuilder withLongitude(BigDecimal lon)   { this.longitude = lon;      return this; }
        public SiteBuilder withActive(boolean active)      { this.active = active;      return this; }

        public Site build() {
            Site s = new Site();
            s.setId(id);
            s.setCustomer(customer);
            s.setName(name);
            s.setAddress(address);
            s.setLatitude(latitude);
            s.setLongitude(longitude);
            s.setActive(active);
            return s;
        }
    }

    // -----------------------------------------------------------------------
    // Asset builder
    // -----------------------------------------------------------------------

    public static final class AssetBuilder {

        private final Site site;
        private UUID id = DeterministicIds.nextId();
        private String name;
        private String assetType;
        private String serialNo;

        private AssetBuilder(Site site) {
            this.site = site;
        }

        public AssetBuilder withId(UUID id)           { this.id = id;          return this; }
        public AssetBuilder withName(String name)     { this.name = name;      return this; }
        public AssetBuilder withAssetType(String t)   { this.assetType = t;    return this; }
        public AssetBuilder withSerialNo(String sn)   { this.serialNo = sn;    return this; }

        public Asset build() {
            Asset a = new Asset();
            a.setId(id);
            a.setSite(site);
            a.setName(name);
            a.setAssetType(assetType);
            a.setSerialNo(serialNo);
            return a;
        }
    }
}
