package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    List<RolePermission> findAllByActiveTrue();

    List<RolePermission> findByRoleNameAndActiveTrue(AppRole roleName);

    Optional<RolePermission> findByRoleNameAndPermissionCode(AppRole roleName, String permissionCode);

    @Query("SELECT COUNT(rp) FROM RolePermission rp WHERE rp.permissionCode = :code AND rp.active = true")
    long countActiveByPermissionCode(@Param("code") String permissionCode);
}
