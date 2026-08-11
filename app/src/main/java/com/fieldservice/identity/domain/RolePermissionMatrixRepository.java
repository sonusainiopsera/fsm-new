package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link RolePermissionMatrix} rows.
 *
 * <p>Not a scoped repository: role-permission data is configuration read by the admin
 * screen, not per-user row-scoped data.
 */
public interface RolePermissionMatrixRepository extends JpaRepository<RolePermissionMatrix, UUID> {

    Optional<RolePermissionMatrix> findByRoleName(IdentityRole roleName);
}
