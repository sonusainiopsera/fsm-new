package com.fieldservice.privacy.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.privacy.api.DryRunReport;
import com.fieldservice.privacy.api.RetentionPolicyService;
import com.fieldservice.privacy.api.RetentionPolicyView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Admin API for viewing and updating the retention policy schedule.
 *
 * <p>All endpoints are restricted to {@code PRIVACY_ADMIN} and {@code ADMIN} roles.
 * A non-privacy role receives 403 with no existence disclosure.
 *
 * <p>GET  /api/v1/privacy/retention-policies          — paginated list (size ≤ 50)<br>
 * GET  /api/v1/privacy/retention-policies/{id}     — single row<br>
 * PUT  /api/v1/privacy/retention-policies/{id}     — update period, disposal, flags<br>
 * POST /api/v1/privacy/retention-policies/{id}/dry-run — eligibility count without deletion
 */
@RestController
@RequestMapping("/api/v1/privacy/retention-policies")
public class RetentionPolicyController {

    private static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            Map.of(
                    "dataCategory",  "dataCategory",
                    "entityName",    "entityName",
                    "periodValue",   "periodValue",
                    "updatedAt",     "updatedAt"
            ),
            "dataCategory"
    );

    private final RetentionPolicyService service;

    public RetentionPolicyController(RetentionPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<PagedResponse<RetentionPolicyResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        PageQuery query    = PageQuery.of(page, size, sort);
        Pageable  pageable = query.toPageable(SORT_ALLOW_LIST);

        Page<RetentionPolicyView> result = service.listPolicies(pageable);

        PageMeta  meta  = PageMeta.of(result.getNumber(), result.getSize(), result.getTotalElements());
        PageLinks links = PageLinks.none();

        PagedResponse<RetentionPolicyResponse> response = PagedResponse.of(
                result.getContent().stream().map(RetentionPolicyResponse::from).toList(),
                meta,
                links);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<RetentionPolicyResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(RetentionPolicyResponse.from(service.getPolicy(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<RetentionPolicyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody RetentionPolicyRequest request,
            Authentication authentication) {

        String actor = authentication != null ? authentication.getName() : "SYSTEM";

        RetentionPolicyView updated = service.updatePolicy(
                id,
                request.periodValue(),
                request.periodUnit(),
                request.disposalMethod(),
                request.legalHold(),
                request.ratified(),
                request.enabled(),
                request.notes(),
                request.version(),
                actor);

        return ResponseEntity.ok(RetentionPolicyResponse.from(updated));
    }

    @PostMapping("/{id}/dry-run")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<DryRunResponse> dryRun(@PathVariable UUID id) {
        DryRunReport report = service.dryRun(id);
        return ResponseEntity.ok(DryRunResponse.from(report));
    }
}
