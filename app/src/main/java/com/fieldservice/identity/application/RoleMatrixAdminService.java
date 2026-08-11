package com.fieldservice.identity.application;

import com.fieldservice.identity.api.dto.RoleMatrixResponse;
import com.fieldservice.identity.api.dto.UpdateRoleMatrixRequest;
import com.fieldservice.identity.domain.IdentityRole;
import com.fieldservice.identity.domain.RolePermissionMatrix;
import com.fieldservice.identity.domain.RolePermissionMatrixRepository;
import com.fieldservice.platform.exception.NotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Admin service for reading and editing the role-to-permission matrix.
 *
 * <p>The {@code role_permission_matrix} table is the declared, audited representation of
 * which permissions belong to each role. The server-side {@code @PreAuthorize} annotations
 * remain the enforcement point; this service records authorised changes so operators
 * have a first-class audit trail with actor, timestamp and before/after values.
 *
 * <p>Guard: removing the last {@code ADMIN} permission is refused with a 422.
 */
@Service
@Transactional(readOnly = true)
public class RoleMatrixAdminService {

    static final String ADMIN_WRITE_PERMISSION = "role-matrix:write";

    private final RolePermissionMatrixRepository repository;

    public RoleMatrixAdminService(RolePermissionMatrixRepository repository) {
        this.repository = repository;
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    public List<RoleMatrixResponse> listMatrix() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getRoleName().name().compareTo(b.getRoleName().name()))
                .map(RoleMatrixResponse::from)
                .toList();
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public RoleMatrixResponse updateMatrix(UpdateRoleMatrixRequest req) {
        IdentityRole role = IdentityRole.valueOf(req.role());

        RolePermissionMatrix entity = repository.findByRoleName(role)
                .orElseThrow(() -> new NotFoundException("RolePermissionMatrix", req.role()));

        // Guard: prevent removing role-matrix:write from ADMIN (locks all admins out)
        if (role == IdentityRole.ADMIN && !req.permissions().contains(ADMIN_WRITE_PERMISSION)) {
            throw new com.fieldservice.platform.exception.BusinessGuardException(
                    "ADMIN_LAST_PERMISSION",
                    "Cannot remove '" + ADMIN_WRITE_PERMISSION
                            + "' from ADMIN: would lock all administrators out of the role matrix.");
        }

        entity.setVersion(req.version());
        entity.setPermissions(String.join(",", req.permissions()));
        RolePermissionMatrix saved = repository.save(entity);
        return RoleMatrixResponse.from(saved);
    }

    /** Returns the permission tokens for a role as a sorted list (for testing). */
    @PreAuthorize("hasAuthority('ADMIN')")
    public List<String> getPermissionsForRole(IdentityRole role) {
        return repository.findByRoleName(role)
                .map(e -> e.getPermissions().isBlank()
                        ? List.<String>of()
                        : Arrays.asList(e.getPermissions().split(",")))
                .orElse(List.of());
    }
}
