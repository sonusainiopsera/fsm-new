package com.fieldservice.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Request DTO for updating the permission set for a given role.
 *
 * <p>The {@code version} field enables optimistic-lock conflict detection (returns 409
 * on mismatch).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateRoleMatrixRequest(

        @NotBlank
        @Pattern(regexp = "ADMIN|DISPATCHER|TECHNICIAN|MANAGER|CUSTOMER|PRIVACY_ADMIN",
                 message = "role must be a valid IdentityRole")
        String role,

        @NotNull
        List<String> permissions,

        @NotNull
        Integer version
) {}
