package com.fieldservice.identity.domain;

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
 * One permission grant in the role-to-permission matrix.
 *
 * <p>Permission codes follow the pattern {@code "RESOURCE:ACTION"} (e.g.
 * {@code "SLA_POLICY:WRITE"}).  Active grants are evaluated at runtime by
 * {@link RolePermissionEvaluator}; inactive grants are soft-deleted and
 * retained for the audit trail.
 *
 * <p>Guard: the last {@code "ADMIN_ACCESS"} grant for the ADMIN role cannot be
 * removed — enforced in {@code RoleMatrixAdminService} to prevent lockout.
 */
@Audited
@Entity
@Table(name = "role_permission")
public class RolePermission {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 50)
    private AppRole roleName;

    @Column(name = "permission_code", nullable = false, length = 100)
    private String permissionCode;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "granted_by", length = 255)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_by", length = 255)
    private String revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private Integer version;

    protected RolePermission() {}

    public static RolePermission grant(AppRole role, String permissionCode, String grantedBy) {
        RolePermission rp = new RolePermission();
        rp.id             = UuidV7.generate();
        rp.roleName       = role;
        rp.permissionCode = permissionCode;
        rp.active         = true;
        rp.grantedBy      = grantedBy;
        rp.grantedAt      = Instant.now();
        return rp;
    }

    public void revoke(String revokedBy) {
        this.active    = false;
        this.revokedBy = revokedBy;
        this.revokedAt = Instant.now();
    }

    public UUID    getId()             { return id; }
    public AppRole getRoleName()       { return roleName; }
    public String  getPermissionCode() { return permissionCode; }
    public boolean isActive()          { return Boolean.TRUE.equals(active); }
    public String  getGrantedBy()      { return grantedBy; }
    public Instant getGrantedAt()      { return grantedAt; }
    public String  getRevokedBy()      { return revokedBy; }
    public Instant getRevokedAt()      { return revokedAt; }
    public Integer getVersion()        { return version; }
}
