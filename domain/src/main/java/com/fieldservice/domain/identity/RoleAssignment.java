package com.fieldservice.domain.identity;

import com.fieldservice.domain.appuser.AppUser;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.UUID;

/**
 * A single role grant for an application user.
 * The {@code role_name} column is constrained by CHECK to the five ratified
 * roles ({@link AppRole}), enforcing the vocabulary at both code and DB level.
 * The user foreign key uses ON DELETE RESTRICT so grant history is never
 * silently destroyed when a user row is removed.
 */
@Entity
@Table(name = "role_assignment")
@Audited
public class RoleAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 50)
    private AppRole roleName;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    protected RoleAssignment() {}

    public RoleAssignment(AppUser user, AppRole roleName, UUID grantedBy) {
        this.id = UuidV7.generate();
        this.user = user;
        this.roleName = roleName;
        this.grantedAt = Instant.now();
        this.grantedBy = grantedBy;
    }

    public UUID getId() { return id; }
    public AppUser getUser() { return user; }
    public AppRole getRoleName() { return roleName; }
    public Instant getGrantedAt() { return grantedAt; }
    public UUID getGrantedBy() { return grantedBy; }
}
