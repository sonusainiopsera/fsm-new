package com.fieldservice.portal.domain;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent linkage record binding a portal (CUSTOMER) user to exactly one customer account.
 *
 * <p>The row is created when an invitation is issued (status=PENDING) and becomes ACTIVE
 * when the invited user accepts and authenticates for the first time.
 *
 * <p>This entity is not a ScopedEntity: it is the infrastructure record used by
 * {@link com.fieldservice.portal.access.CustomerAccessScope} to resolve the caller's
 * account_id. Portal query predicates are emitted from that scope resolver, not from
 * JPA Specification machinery on this table.
 *
 * <p>Envers audits every mutation so account linkage changes are immutably logged.
 */
@Audited
@Entity
@Table(name = "portal_account_user")
public class PortalAccountUser extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PortalAccountUserStatus status;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected PortalAccountUser() {
    }

    public PortalAccountUser(UUID userId, UUID accountId) {
        this.userId = userId;
        this.accountId = accountId;
        this.status = PortalAccountUserStatus.PENDING;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public PortalAccountUserStatus getStatus() {
        return status;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void activate(Instant at) {
        this.status = PortalAccountUserStatus.ACTIVE;
        this.activatedAt = at;
    }

    public void suspend() {
        this.status = PortalAccountUserStatus.SUSPENDED;
    }
}
