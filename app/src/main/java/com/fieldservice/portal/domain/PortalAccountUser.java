package com.fieldservice.portal.domain;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted linkage between an identity user and exactly one customer account.
 *
 * <p>Maps to {@code portal_account_user}. The UNIQUE constraint on {@code user_id}
 * enforces the one-to-one invariant at the database level.
 *
 * <p>Row-scoped: the {@link PortalAccountUserScopeSpec} restricts reads to the row
 * whose {@code user_id} matches the authenticated principal's userId so a principal
 * can never read another principal's linkage record.
 */
@Audited
@Entity
@Table(name = "portal_account_user")
public class PortalAccountUser implements ScopedEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PortalAccountStatus status = PortalAccountStatus.ACTIVE;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private Integer version;

    protected PortalAccountUser() {}

    /** Creates an ACTIVE linkage row for a user already verified by an administrator. */
    public PortalAccountUser(UUID userId, UUID accountId) {
        this.id          = UuidV7.generate();
        this.userId      = userId;
        this.accountId   = accountId;
        this.status      = PortalAccountStatus.ACTIVE;
        this.activatedAt = Instant.now();
    }

    /** Creates a PENDING linkage row awaiting invitation activation. */
    public PortalAccountUser(UUID userId, UUID accountId, PortalAccountStatus status) {
        this.id        = UuidV7.generate();
        this.userId    = userId;
        this.accountId = accountId;
        this.status    = status;
    }

    /** For tests that need a deterministic id. */
    public PortalAccountUser(UUID id, UUID userId, UUID accountId) {
        this.id          = id;
        this.userId      = userId;
        this.accountId   = accountId;
        this.status      = PortalAccountStatus.ACTIVE;
        this.activatedAt = Instant.now();
    }

    public void activate(Instant now) {
        this.status      = PortalAccountStatus.ACTIVE;
        this.activatedAt = now;
    }

    public void suspend() {
        this.status = PortalAccountStatus.SUSPENDED;
    }

    public UUID                getId()          { return id; }
    public UUID                getUserId()      { return userId; }
    public UUID                getAccountId()   { return accountId; }
    public PortalAccountStatus getStatus()      { return status; }
    public Instant             getActivatedAt() { return activatedAt; }
    public Instant             getCreatedAt()   { return createdAt; }
    public Integer             getVersion()     { return version; }

    public boolean isActive() {
        return status == PortalAccountStatus.ACTIVE;
    }
}
