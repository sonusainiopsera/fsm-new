package com.fieldservice.domain.site;

import com.fieldservice.domain.customer.Customer;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer site where field service work is performed.
 *
 * <p>Scoped entity: CUSTOMER principals may only read sites belonging to their linked
 * customer accounts. DISPATCHER, ADMIN, and MANAGER see all sites.
 */
@Audited
@Entity
@Table(name = "site")
public class Site extends BaseEntity implements ScopedEntity {

    // UUID FK field is audited — stores customer_id in site_aud
    @Column(name = "customer_id", nullable = false, insertable = false, updatable = false)
    private UUID customerId;

    // @ManyToOne excluded — UUID field above handles the FK column in the audit table
    @NotAudited
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "latitude", precision = 9, scale = 6)
    private java.math.BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private java.math.BigDecimal longitude;

    @Column(name = "site_code", length = 50)
    private String siteCode;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "postcode", length = 20)
    private String postcode;

    @Column(name = "access_notes", columnDefinition = "TEXT")
    private String accessNotes;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected Site() {
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
        this.customerId = customer != null ? customer.getId() : null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public java.math.BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(java.math.BigDecimal latitude) {
        this.latitude = latitude;
    }

    public java.math.BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(java.math.BigDecimal longitude) {
        this.longitude = longitude;
    }

    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPostcode() { return postcode; }
    public void setPostcode(String postcode) { this.postcode = postcode; }

    public String getAccessNotes() { return accessNotes; }
    public void setAccessNotes(String accessNotes) { this.accessNotes = accessNotes; }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(Instant deactivatedAt) { this.deactivatedAt = deactivatedAt; }
}
