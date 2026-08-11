package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of which permissions are granted to each role.
 *
 * <p>Permissions are stored as a comma-delimited string so Envers can capture
 * before/after values in a single audit row. The string is the canonical source of
 * truth; the server-side {@code @PreAuthorize} annotations are the enforcement point.
 *
 * <p>One row per {@link IdentityRole}. Version column enables optimistic-lock conflict
 * detection on concurrent admin edits (returns 409).
 */
@Audited
@Entity
@Table(name = "role_permission_matrix")
public class RolePermissionMatrix {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, unique = true, length = 20, updatable = false)
    private IdentityRole roleName;

    /** Comma-delimited permission tokens, e.g. {@code "sla:read,sla:write"}. */
    @Column(name = "permissions", nullable = false)
    private String permissions = "";

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RolePermissionMatrix() {}

    public RolePermissionMatrix(IdentityRole roleName, String permissions) {
        this.roleName = roleName;
        this.permissions = permissions;
    }

    public UUID getId() { return id; }

    public IdentityRole getRoleName() { return roleName; }

    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Instant getUpdatedAt() { return updatedAt; }
}
