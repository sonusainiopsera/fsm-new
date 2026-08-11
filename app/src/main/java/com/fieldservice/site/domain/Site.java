package com.fieldservice.site.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer site where field service work orders are executed.
 *
 * <p>Row-scoped: a CUSTOMER principal may only see sites belonging to one of their linked
 * customer accounts. The {@code customerId} column is the FK used by the scope predicate —
 * stored as a plain UUID rather than a JPA association to keep the scope predicate simple
 * and to avoid N+1 joins.
 */
@Audited
@Entity
@Table(name = "site")
public class Site implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    /** FK to customer.id — used directly in the CUSTOMER scope predicate. */
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 20)
    private String postcode;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "site_code", length = 20)
    private String siteCode;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "access_notes")
    private String accessNotes;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private Integer version;

    protected Site() {}

    public Site(String name, UUID customerId) {
        this.id         = UuidV7.generate();
        this.name       = name;
        this.customerId = customerId;
    }

    public Site(String siteCode, String displayName, UUID customerId) {
        this.id          = UuidV7.generate();
        this.siteCode    = siteCode;
        this.displayName = displayName;
        this.name        = displayName;
        this.customerId  = customerId;
    }

    /** For tests that need a deterministic id. */
    public Site(UUID id, String name, UUID customerId) {
        this.id         = id;
        this.name       = name;
        this.customerId = customerId;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void deactivate(Instant now) {
        this.active        = false;
        this.deactivatedAt = now;
        this.updatedAt     = now;
    }

    public void update(String displayName, String addressLine1, String addressLine2,
                       String city, String postcode, BigDecimal latitude, BigDecimal longitude,
                       String accessNotes, UUID actorId) {
        this.displayName  = displayName;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city         = city;
        this.postcode     = postcode;
        this.latitude     = latitude;
        this.longitude    = longitude;
        this.accessNotes  = accessNotes;
        this.updatedBy    = actorId;
    }

    public UUID       getId()           { return id; }
    public String     getName()         { return name; }
    public UUID       getCustomerId()   { return customerId; }
    public String     getAddressLine1() { return addressLine1; }
    public String     getAddressLine2() { return addressLine2; }
    public String     getCity()         { return city; }
    public String     getPostcode()     { return postcode; }
    public BigDecimal getLatitude()     { return latitude; }
    public BigDecimal getLongitude()    { return longitude; }
    public String     getSiteCode()     { return siteCode; }
    public String     getDisplayName()  { return displayName; }
    public String     getAccessNotes()  { return accessNotes; }
    public boolean    isActive()        { return Boolean.TRUE.equals(active); }
    public Instant    getDeactivatedAt(){ return deactivatedAt; }
    public Instant    getCreatedAt()    { return createdAt; }
    public UUID       getCreatedBy()    { return createdBy; }
    public Instant    getUpdatedAt()    { return updatedAt; }
    public UUID       getUpdatedBy()    { return updatedBy; }
    public Integer    getVersion()      { return version; }
}
