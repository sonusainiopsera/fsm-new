package com.fieldservice.customer.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * A customer account that owns one or more service sites.
 * Row-scoped: a CUSTOMER principal may only read accounts listed in their
 * {@link com.fieldservice.platform.security.AccessScope#customerAccountIds()} set.
 */
@Entity
@Table(name = "customer_account")
public class CustomerAccount implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Version
    private Long version;

    protected CustomerAccount() {}

    public CustomerAccount(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Long getVersion() { return version; }
}
