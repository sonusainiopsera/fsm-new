package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a single role grant for an {@link AppUser}.
 *
 * <p>The unique constraint on (user_id, role_name) prevents duplicate grants.
 * The CHECK constraint on role_name enforces the vocabulary in the database so a
 * grant script cannot introduce an out-of-vocabulary role.
 *
 * <p>ON DELETE RESTRICT on user_id refuses app_user deletion when grants still exist,
 * preserving grant history rather than cascading.
 */
@Audited
@Entity
@Table(name = "role_assignment")
public class RoleAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 50)
    private AppRole roleName;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", length = 255)
    private String grantedBy;

    protected RoleAssignment() {}

    public static RoleAssignment grant(AppUser user, AppRole role, String grantedBy) {
        RoleAssignment ra = new RoleAssignment();
        ra.id = UuidV7.generate();
        ra.user = user;
        ra.roleName = role;
        ra.grantedAt = Instant.now();
        ra.grantedBy = grantedBy;
        return ra;
    }

    public UUID      getId()        { return id; }
    public AppUser   getUser()      { return user; }
    public AppRole   getRoleName()  { return roleName; }
    public Instant   getGrantedAt() { return grantedAt; }
    public String    getGrantedBy() { return grantedBy; }
}
