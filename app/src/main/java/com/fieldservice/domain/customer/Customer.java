package com.fieldservice.domain.customer;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A business customer entity that owns one or more sites.
 *
 * <p>The {@code customer.id} is the row-scope boundary for CUSTOMER principals.
 * It corresponds to the UUIDs in the JWT {@code customerAccountIds} claim.
 *
 * <p>Data classification: Confidential (contact details), Internal (business fields).
 * Retention: purge 12 months after {@code relationship_ended_on} per the data register.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>CUSTOMER — sees only their own customer record(s).</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>TECHNICIAN — permit-all (technicians need customer names for work orders).</li>
 * </ul>
 */
@DataClassification(tier = ClassificationTier.CONFIDENTIAL, note = "Customer aggregate contains contact PII; lawful basis: B2B service contract")
@Audited
@Entity
@Table(name = "customer")
public class Customer extends BaseEntity implements ScopedEntity {

    /** Legacy name column from V1 — kept for backward compatibility; use {@link #legalName} for new code. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "account_code", length = 50)
    private String accountCode;

    @Column(name = "legal_name", length = 255)
    private String legalName;

    /** Confidential — contact PII; never log, never include in event payloads. */
    @Column(name = "primary_contact_name", length = 255)
    private String primaryContactName;

    /** Confidential — contact PII. */
    @Column(name = "primary_contact_email", length = 320)
    private String primaryContactEmail;

    /** Confidential — contact PII. */
    @Column(name = "primary_contact_phone", length = 50)
    private String primaryContactPhone;

    /** Legacy contact fields from V1 — kept for backward compat; prefer primary_contact_* for new code. */
    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "relationship_ended_on")
    private LocalDate relationshipEndedOn;

    protected Customer() {
    }

    // -------------------------------------------------------------------------
    // Legacy accessor kept for backward compat with existing V100 fixtures / scopes
    // -------------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    // -------------------------------------------------------------------------
    // New catalog fields
    // -------------------------------------------------------------------------

    public String getAccountCode() { return accountCode; }
    public void setAccountCode(String accountCode) { this.accountCode = accountCode; }

    public String getLegalName() { return legalName; }
    public void setLegalName(String legalName) { this.legalName = legalName; }

    public String getPrimaryContactName() { return primaryContactName; }
    public void setPrimaryContactName(String primaryContactName) { this.primaryContactName = primaryContactName; }

    public String getPrimaryContactEmail() { return primaryContactEmail; }
    public void setPrimaryContactEmail(String primaryContactEmail) { this.primaryContactEmail = primaryContactEmail; }

    public String getPrimaryContactPhone() { return primaryContactPhone; }
    public void setPrimaryContactPhone(String primaryContactPhone) { this.primaryContactPhone = primaryContactPhone; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getBillingAddress() { return billingAddress; }
    public void setBillingAddress(String billingAddress) { this.billingAddress = billingAddress; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(Instant deactivatedAt) { this.deactivatedAt = deactivatedAt; }

    public LocalDate getRelationshipEndedOn() { return relationshipEndedOn; }
    public void setRelationshipEndedOn(LocalDate relationshipEndedOn) { this.relationshipEndedOn = relationshipEndedOn; }
}
