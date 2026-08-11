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

import java.util.UUID;

/**
 * A customer site where field service work is performed.
 *
 * <p>Scoped entity: CUSTOMER principals may only read sites belonging to their linked
 * customer accounts. DISPATCHER, ADMIN, and MANAGER see all sites.
 */
@Entity
@Table(name = "site")
public class Site extends BaseEntity implements ScopedEntity {

    @Column(name = "customer_id", nullable = false, insertable = false, updatable = false)
    private UUID customerId;

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

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
