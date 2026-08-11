package com.fieldservice.customer.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer that owns one or more service sites.
 * Maps to the {@code customer} table.
 *
 * <p>Row-scoped: a CUSTOMER principal may only read accounts listed in their
 * {@link com.fieldservice.platform.security.AccessScope#customerAccountIds()} set.
 */
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected CustomerAccount() {}

    public CustomerAccount(String name) {
        this.id   = UuidV7.generate();
        this.name = name;
    }

    /** For tests that need a deterministic id. */
    public CustomerAccount(UUID id, String name) {
        this.id   = id;
        this.name = name;
    }

    public UUID    getId()           { return id; }
    public String  getName()         { return name; }
    public String  getContactEmail() { return contactEmail; }
    public String  getPhone()        { return phone; }
    public Instant getCreatedAt()    { return createdAt; }
    public Integer getVersion()      { return version; }
}
