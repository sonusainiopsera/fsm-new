package com.fieldservice.identity.api;

import com.fieldservice.identity.api.dto.RoleMatrixResponse;
import com.fieldservice.identity.api.dto.UpdateRoleMatrixRequest;
import com.fieldservice.identity.application.RoleMatrixAdminService;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only REST endpoints for viewing and editing the RBAC role-to-permission matrix.
 *
 * <p>Access is ADMIN-only, enforced server-side. The role matrix screen makes it
 * clear that {@code @PreAuthorize} annotations are the enforcement point; the matrix
 * is the declared, audited state of what permissions each role holds.
 */
@RestController
@RequestMapping("/api/v1/admin/role-matrix")
@Tag(name = "Admin Role Matrix", description = "Admin-only RBAC role-to-permission matrix")
@PreAuthorize("hasAuthority('ADMIN')")
public class RoleMatrixAdminController {

    private final RoleMatrixAdminService roleMatrixAdminService;

    public RoleMatrixAdminController(RoleMatrixAdminService roleMatrixAdminService) {
        this.roleMatrixAdminService = roleMatrixAdminService;
    }

    @Operation(operationId = "listRoleMatrix", summary = "List role-to-permission matrix (ADMIN)")
    @GetMapping
    public ResponseEntity<PagedResponse<RoleMatrixResponse>> list() {
        List<RoleMatrixResponse> entries = roleMatrixAdminService.listMatrix();
        PageMeta  meta  = PageMeta.of(0, entries.size(), (long) entries.size());
        PageLinks links = PageLinks.none();
        return ResponseEntity.ok(PagedResponse.of(entries, meta, links));
    }

    @Operation(operationId = "updateRoleMatrix", summary = "Update permissions for a role (ADMIN)")
    @PutMapping
    public ResponseEntity<RoleMatrixResponse> update(
            @Valid @RequestBody UpdateRoleMatrixRequest req) {
        RoleMatrixResponse updated = roleMatrixAdminService.updateMatrix(req);
        return ResponseEntity.ok(updated);
    }
}
