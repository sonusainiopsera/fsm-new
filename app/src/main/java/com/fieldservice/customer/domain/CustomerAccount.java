package com.fieldservice.customer.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer that owns one or more service sites.
 * Maps to the {@code customer} table.
 *
 * <p>Row-scoped: a CUSTOMER principal may only read accounts listed in their
 * {@link com.fieldservice.platform.security.AccessScope#customerAccountIds()} set.
 */
@Audited
@Entity
@Table(name = "customer")
public class CustomerAccount implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(length = 50)
    private String phone;

    @Column(name = "account_code", length = 20)
    private String accountCode;

    @Column(name = "legal_name", length = 255)
    private String legalName;

    @Column(name = "primary_contact_name", length = 255)
    private String primaryContactName;

    @Column(name = "primary_contact_email", length = 255)
    private String primaryContactEmail;

    @Column(name = "primary_contact_phone", length = 50)
    private String primaryContactPhone;

    @Column(name = "billing_address")
    private String billingAddress;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "relationship_ended_on")
    private LocalDate relationshipEndedOn;

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

    protected CustomerAccount() {}

    public CustomerAccount(String name) {
        this.id   = UuidV7.generate();
        this.name = name;
    }

    public CustomerAccount(String accountCode, String legalName) {
        this.id          = UuidV7.generate();
        this.accountCode = accountCode;
        this.legalName   = legalName;
        this.name        = legalName;
    }

    /** For tests that need a deterministic id. */
    public CustomerAccount(UUID id, String name) {
        this.id   = id;
        this.name = name;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void deactivate(Instant now) {
        this.active         = false;
        this.deactivatedAt  = now;
        this.updatedAt      = now;
    }

    public void update(String legalName, String primaryContactName, String primaryContactEmail,
                       String primaryContactPhone, String billingAddress, UUID actorId) {
        this.legalName            = legalName;
        this.primaryContactName   = primaryContactName;
        this.primaryContactEmail  = primaryContactEmail;
        this.primaryContactPhone  = primaryContactPhone;
        this.billingAddress       = billingAddress;
        this.updatedBy            = actorId;
    }

    public UUID      getId()                  { return id; }
    public String    getName()                { return name; }
    public String    getContactEmail()        { return contactEmail; }
    public String    getPhone()               { return phone; }
    public String    getAccountCode()         { return accountCode; }
    public String    getLegalName()           { return legalName; }
    public String    getPrimaryContactName()  { return primaryContactName; }
    public String    getPrimaryContactEmail() { return primaryContactEmail; }
    public String    getPrimaryContactPhone() { return primaryContactPhone; }
    public String    getBillingAddress()      { return billingAddress; }
    public boolean   isActive()               { return Boolean.TRUE.equals(active); }
    public Instant   getDeactivatedAt()       { return deactivatedAt; }
    public LocalDate getRelationshipEndedOn() { return relationshipEndedOn; }
    public Instant   getCreatedAt()           { return createdAt; }
    public UUID      getCreatedBy()           { return createdBy; }
    public Instant   getUpdatedAt()           { return updatedAt; }
    public UUID      getUpdatedBy()           { return updatedBy; }
    public Integer   getVersion()             { return version; }
}
