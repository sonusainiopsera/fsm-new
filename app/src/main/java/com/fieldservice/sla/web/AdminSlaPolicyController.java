package com.fieldservice.sla.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.sla.SlaPolicyAdminService;
import com.fieldservice.sla.web.dto.CreateSlaPolicyRequest;
import com.fieldservice.sla.web.dto.SlaPolicyResponse;
import com.fieldservice.sla.web.dto.UpdateSlaPolicyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only REST endpoints for managing SLA policies.
 *
 * <p>All operations require the {@code ADMIN} authority enforced server-side via
 * {@code @PreAuthorize}. Any non-ADMIN token (DISPATCHER, TECHNICIAN, MANAGER,
 * CUSTOMER) receives 403 with no internal detail disclosed.
 *
 * <p>PUT performs a direct in-place update with optimistic locking (version field
 * required). A 409 is returned on concurrent-edit conflicts.
 */
@RestController
@RequestMapping("/api/v1/admin/sla-policies")
@Tag(name = "Admin SLA Policies", description = "Admin-only SLA policy configuration")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminSlaPolicyController {

    static final int MAX_PAGE_SIZE = 50;

    private final SlaPolicyAdminService slaPolicyAdminService;

    public AdminSlaPolicyController(SlaPolicyAdminService slaPolicyAdminService) {
        this.slaPolicyAdminService = slaPolicyAdminService;
    }

    @Operation(operationId = "listSlaPolicies", summary = "List all SLA policies (ADMIN)")
    @GetMapping
    public ResponseEntity<PagedResponse<SlaPolicyResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);
        List<SlaPolicyResponse> all = slaPolicyAdminService.listPolicies().stream()
                .map(SlaPolicyResponse::from)
                .toList();

        int total = all.size();
        int fromIndex = Math.min(page * effectiveSize, total);
        int toIndex   = Math.min(fromIndex + effectiveSize, total);
        List<SlaPolicyResponse> slice = all.subList(fromIndex, toIndex);

        PageMeta  meta  = PageMeta.of(page, effectiveSize, (long) total);
        PageLinks links = PageLinks.none();
        return ResponseEntity.ok(PagedResponse.of(slice, meta, links));
    }

    @Operation(operationId = "createSlaPolicy", summary = "Create a new SLA policy (ADMIN)")
    @PostMapping
    public ResponseEntity<SlaPolicyResponse> create(
            @Valid @RequestBody CreateSlaPolicyRequest req,
            UriComponentsBuilder uriBuilder) {

        SlaPolicyResponse saved = SlaPolicyResponse.from(slaPolicyAdminService.createPolicy(req));
        var location = uriBuilder.path("/api/v1/admin/sla-policies/{id}")
                .buildAndExpand(saved.id())
                .toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @Operation(operationId = "updateSlaPolicy", summary = "Update an SLA policy in place (ADMIN)")
    @PutMapping("/{id}")
    public ResponseEntity<SlaPolicyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSlaPolicyRequest req) {

        SlaPolicyResponse updated = SlaPolicyResponse.from(
                slaPolicyAdminService.updatePolicy(id, req));
        return ResponseEntity.ok(updated);
    }
}
