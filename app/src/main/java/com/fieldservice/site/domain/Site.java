package com.fieldservice.site.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected Site() {}

    public Site(String name, UUID customerId) {
        this.id         = UuidV7.generate();
        this.name       = name;
        this.customerId = customerId;
    }

    /** For tests that need a deterministic id. */
    public Site(UUID id, String name, UUID customerId) {
        this.id         = id;
        this.name       = name;
        this.customerId = customerId;
    }

    public UUID    getId()         { return id; }
    public String  getName()       { return name; }
    public UUID    getCustomerId() { return customerId; }
    public Instant getCreatedAt()  { return createdAt; }
    public Integer getVersion()    { return version; }
}
