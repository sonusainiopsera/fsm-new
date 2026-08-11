package com.fieldservice.privacy.web;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.privacy.api.DryRunReport;
import com.fieldservice.privacy.api.RetentionPolicyAdminPort;
import com.fieldservice.privacy.api.RetentionPolicyView;
import com.fieldservice.privacy.api.UpdateRetentionPolicyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin API for reviewing and adjusting the retention schedule.
 *
 * <p>All three endpoints require {@code PRIVACY_ADMIN} or {@code ADMIN} role, enforced
 * via {@code @PreAuthorize} on the delegated service methods — this controller contains
 * no security logic itself.
 *
 * <p>Error responses use the shared structured contract
 * {@code {code, message, fieldErrors[], traceId}} (no stack traces, A10):
 * <ul>
 *   <li>400 — invalid period unit, non-positive period value, unknown disposal method</li>
 *   <li>403 — insufficient role</li>
 *   <li>404 — policy id not found</li>
 *   <li>409 — stale version (optimistic lock conflict)</li>
 *   <li>422 — audit retention floor violation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/privacy/retention-policies")
@Tag(name = "Privacy - Retention Policies", description = "Data retention schedule admin API")
public class RetentionPolicyController {

    private final RetentionPolicyAdminPort adminPort;

    public RetentionPolicyController(RetentionPolicyAdminPort adminPort) {
        this.adminPort = adminPort;
    }

    @Operation(
            operationId = "listRetentionPolicies",
            summary = "List retention policies (paginated, max page size 50)"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<RetentionPolicyView>> listRetentionPolicies(
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(adminPort.listPolicies(pageQuery, request));
    }

    @Operation(
            operationId = "updateRetentionPolicy",
            summary = "Update retention period, disposal method and flags for a policy row"
    )
    @PutMapping(value = "/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RetentionPolicyView> updateRetentionPolicy(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRetentionPolicyRequest body) {
        return ResponseEntity.ok(adminPort.updatePolicy(id, body));
    }

    @Operation(
            operationId = "dryRunRetentionPolicy",
            summary = "Dry-run: count eligible rows for disposal without mutating data"
    )
    @PostMapping(value = "/{id}/dry-run",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DryRunReport> dryRun(
            @PathVariable UUID id) {
        return ResponseEntity.ok(adminPort.dryRun(id));
    }
}
