package com.fieldservice.domain.customer;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A business customer entity that owns one or more sites.
 *
 * <p>The {@code customer.id} is the row-scope boundary for CUSTOMER principals.
 * It corresponds to the UUIDs in the JWT {@code customerAccountIds} claim.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>CUSTOMER — sees only their own customer record(s).</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>TECHNICIAN — permit-all (technicians need customer names for work orders).</li>
 * </ul>
 */
@Entity
@Table(name = "customer")
public class Customer extends BaseEntity implements ScopedEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Customer() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getBillingAddress() { return billingAddress; }
    public void setBillingAddress(String billingAddress) { this.billingAddress = billingAddress; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
