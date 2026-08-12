package com.fieldservice.identity.web;

import com.fieldservice.identity.application.RoleMatrixAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only REST endpoints for reading and updating the role-to-permission matrix.
 *
 * <p>GET  /api/v1/admin/role-matrix — returns the full active matrix
 * <p>PUT  /api/v1/admin/role-matrix — replaces the permission set for one role
 *
 * <p>Both operations are gated by {@code hasRole('ADMIN')} at the controller
 * level and again at the service level ({@link RoleMatrixAdminService}).
 */
@RestController
@RequestMapping("/api/v1/admin/role-matrix")
@PreAuthorize("hasRole('ADMIN')")
public class RoleMatrixAdminController {

    private final RoleMatrixAdminService service;

    public RoleMatrixAdminController(RoleMatrixAdminService service) {
        this.service = service;
    }

    /** Returns the full active role-to-permission matrix. */
    @GetMapping
    public ResponseEntity<RoleMatrixResponse> getMatrix() {
        return ResponseEntity.ok(service.readMatrix());
    }

    /**
     * Replaces the active permission set for the specified role.
     *
     * <p>Permissions present in the current matrix but absent from the request
     * are revoked. Permissions newly added in the request are granted.
     * Attempting to remove the last {@code ADMIN_ACCESS} grant returns 422.
     */
    @PutMapping
    public ResponseEntity<RoleMatrixResponse> updateRole(
            @Valid @RequestBody RoleMatrixUpdateRequest request) {
        return ResponseEntity.ok(service.updateRole(request));
    }
}
