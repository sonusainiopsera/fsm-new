package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.RolePermissionRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Spring Security permission evaluator for dynamic role-permission matrix checks.
 *
 * <p>Usage in {@code @PreAuthorize}:
 * <pre>{@code
 *   @PreAuthorize("@rolePermissionEvaluator.hasPermission(authentication, 'SLA_POLICY:WRITE')")
 * }</pre>
 *
 * <p>Returns {@code true} if the authenticated user holds any role that has the
 * requested permission code active in the {@code role_permission} table.
 *
 * <p>No caching: queries are per-request so matrix changes take effect immediately.
 * For high-frequency paths, add a short-lived cache here and evict on
 * {@link RoleMatrixAdminService#updateRole}.
 */
@Component("rolePermissionEvaluator")
public class RolePermissionEvaluator {

    private final RolePermissionRepository repo;

    public RolePermissionEvaluator(RolePermissionRepository repo) {
        this.repo = repo;
    }

    /**
     * Returns {@code true} if ANY of the caller's granted roles has {@code permissionCode}
     * active in the role_permission table.
     */
    public boolean hasPermission(Authentication auth, String permissionCode) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name.startsWith("ROLE_")) {
                name = name.substring(5);
            }
            try {
                AppRole role = AppRole.valueOf(name);
                if (repo.findByRoleNameAndPermissionCode(role, permissionCode)
                        .filter(rp -> rp.isActive())
                        .isPresent()) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // authority not in AppRole vocabulary — skip
            }
        }
        return false;
    }
}
