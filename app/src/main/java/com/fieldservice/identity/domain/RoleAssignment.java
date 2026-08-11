package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable record of a role being granted to a user.
 *
 * <p>The {@code (user_id, role_name)} unique constraint prevents duplicate grants.
 * The database-level {@code ON DELETE RESTRICT} on {@code user_id} prevents silent
 * destruction of grant history when a user row is deleted.
 *
 * <p>Grants are append-only: they are not updated after creation. Revoking a role
 * means deleting this row, which produces an Envers DELETE revision for audit.
 */
@Audited
@Entity
@Table(name = "role_assignment")
public class RoleAssignment {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** FK to {@code app_user.id}; ON DELETE RESTRICT enforced at the DB level. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, updatable = false, length = 20)
    private IdentityRole roleName;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    /** UUID of the user who performed the grant; null for bootstrap/system grants. */
    @Column(name = "granted_by")
    private UUID grantedBy;

    protected RoleAssignment() {}

    public RoleAssignment(UUID userId, IdentityRole roleName, Instant grantedAt, UUID grantedBy) {
        this.userId = userId;
        this.roleName = roleName;
        this.grantedAt = grantedAt;
        this.grantedBy = grantedBy;
    }

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public IdentityRole getRoleName() { return roleName; }

    public Instant getGrantedAt() { return grantedAt; }

    public UUID getGrantedBy() { return grantedBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoleAssignment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : System.identityHashCode(this);
    }
}
