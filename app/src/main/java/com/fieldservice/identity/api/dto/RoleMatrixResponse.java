package com.fieldservice.identity.api.dto;

import com.fieldservice.identity.domain.RolePermissionMatrix;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a single role-permission entry.
 */
public record RoleMatrixResponse(
        UUID id,
        String role,
        List<String> permissions,
        Integer version,
        Instant updatedAt
) {
    public static RoleMatrixResponse from(RolePermissionMatrix entity) {
        List<String> perms = entity.getPermissions().isBlank()
                ? List.of()
                : Arrays.asList(entity.getPermissions().split(","));
        return new RoleMatrixResponse(
                entity.getId(),
                entity.getRoleName().name(),
                perms,
                entity.getVersion(),
                entity.getUpdatedAt());
    }
}
