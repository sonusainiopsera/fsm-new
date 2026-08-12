package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.RolePermission;
import com.fieldservice.identity.domain.RolePermissionRepository;
import com.fieldservice.identity.web.RoleMatrixResponse;
import com.fieldservice.identity.web.RoleMatrixUpdateRequest;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin service for reading and updating the role-to-permission matrix.
 *
 * <p>All mutating operations are ADMIN-only and produce Envers audit revisions.
 *
 * <h2>Guard</h2>
 * The last {@code ADMIN_ACCESS} grant for the ADMIN role cannot be removed.
 * This prevents every administrator from being locked out of the system.
 *
 * <h2>Enforcement</h2>
 * The role matrix is evaluated by {@link RolePermissionEvaluator} on any
 * method annotated with a permission-expression.  Updating the matrix takes
 * effect on the next request (no cache, no restart required).
 */
@Service
@Transactional
public class RoleMatrixAdminService {

    private static final Logger log = LoggerFactory.getLogger(RoleMatrixAdminService.class);
    private static final String ADMIN_LOCK_PERMISSION = "ADMIN_ACCESS";

    private final RolePermissionRepository repo;

    public RoleMatrixAdminService(RolePermissionRepository repo) {
        this.repo = repo;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public RoleMatrixResponse readMatrix() {
        Map<AppRole, List<String>> matrix = repo.findAllByActiveTrue()
                .stream()
                .collect(Collectors.groupingBy(
                        RolePermission::getRoleName,
                        Collectors.mapping(RolePermission::getPermissionCode, Collectors.toList())
                ));
        return RoleMatrixResponse.from(matrix);
    }

    /**
     * Replaces the active permission set for a single role.
     *
     * <p>Revokes permissions no longer in the new set and grants newly added ones.
     * Idempotent: re-granting an already-active permission is a no-op.
     *
     * @throws BusinessGuardException if the operation would remove the last ADMIN_ACCESS grant
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RoleMatrixResponse updateRole(RoleMatrixUpdateRequest request) {
        AppRole role       = AppRole.valueOf(request.role());
        Set<String> newSet = Set.copyOf(request.permissions());
        String actor       = resolveActor();

        // Guard: refuse if this update removes the last ADMIN_ACCESS entry
        if (role == AppRole.ADMIN && !newSet.contains(ADMIN_LOCK_PERMISSION)) {
            long otherAdminAccess = repo.countActiveByPermissionCode(ADMIN_LOCK_PERMISSION);
            long thisRoleHasIt    = repo.findByRoleNameAndPermissionCode(role, ADMIN_LOCK_PERMISSION)
                    .filter(RolePermission::isActive).isPresent() ? 1L : 0L;
            if (otherAdminAccess - thisRoleHasIt < 1) {
                throw new BusinessGuardException(
                        "Cannot remove ADMIN_ACCESS from ADMIN role — at least one active "
                        + "ADMIN_ACCESS grant must remain to prevent lockout.");
            }
        }

        List<RolePermission> existing = repo.findByRoleNameAndActiveTrue(role);
        Set<String> existingCodes = existing.stream()
                .map(RolePermission::getPermissionCode)
                .collect(Collectors.toSet());

        // Revoke permissions no longer in the new set
        for (RolePermission rp : existing) {
            if (!newSet.contains(rp.getPermissionCode())) {
                rp.revoke(actor);
                repo.save(rp);
                log.info("role_permission_revoked role={} permission={} actor={}",
                        role, rp.getPermissionCode(), actor);
            }
        }

        // Grant permissions newly added
        for (String code : newSet) {
            if (!existingCodes.contains(code)) {
                repo.save(RolePermission.grant(role, code, actor));
                log.info("role_permission_granted role={} permission={} actor={}",
                        role, code, actor);
            }
        }

        return readMatrix();
    }

    private String resolveActor() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "system";
        } catch (Exception ignored) {
            return "system";
        }
    }
}
