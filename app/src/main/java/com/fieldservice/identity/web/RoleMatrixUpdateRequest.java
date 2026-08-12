package com.fieldservice.identity.web;

import com.fieldservice.platform.validation.ValidEnum;
import com.fieldservice.identity.domain.AppRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for PUT /api/v1/admin/role-matrix.
 *
 * <p>Replaces the entire active permission set for the specified role.
 * Any permission currently held by the role and NOT in the new list is revoked.
 * Any permission in the new list and NOT currently held is granted.
 *
 * <p>Guard: attempting to clear all {@code ADMIN_ACCESS} grants returns 422.
 */
public record RoleMatrixUpdateRequest(

        @NotNull
        @ValidEnum(enumClass = AppRole.class)
        String role,

        @NotNull @Size(max = 50, message = "A role may not hold more than 50 permissions")
        List<@NotNull String> permissions
) {}
