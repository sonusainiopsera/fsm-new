package com.fieldservice.site.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * A customer site where field service work orders are executed.
 *
 * <p>Row-scoped: a CUSTOMER principal may only see sites belonging to one of their linked
 * customer accounts. The {@code customerAccountId} column is the FK used by the scope
 * predicate — stored as a plain UUID rather than a JPA association to keep the scope
 * predicate simple and to avoid N+1 joins.
 */
@Entity
@Table(name = "site")
public class Site implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    /** FK to customer_account.id — used directly in the CUSTOMER scope predicate. */
    @Column(name = "customer_account_id", nullable = false)
    private UUID customerAccountId;

    @Version
    private Long version;

    protected Site() {}

    public Site(UUID id, String name, UUID customerAccountId) {
        this.id = id;
        this.name = name;
        this.customerAccountId = customerAccountId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getCustomerAccountId() { return customerAccountId; }
    public Long getVersion() { return version; }
}
