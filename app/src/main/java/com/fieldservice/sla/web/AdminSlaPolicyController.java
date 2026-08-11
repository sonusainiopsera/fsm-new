package com.fieldservice.sla.web;

import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.sla.domain.SlaPolicy;
import com.fieldservice.sla.internal.SlaPolicyService;
import com.fieldservice.platform.api.exception.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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

import java.util.List;
import java.util.UUID;

/**
 * Admin-only CRUD for SLA policies.
 * All operations require ADMIN role; gate enforced both at method and controller level.
 */
@RestController
@RequestMapping("/api/v1/admin/sla-policies")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSlaPolicyController {

    private final SlaPolicyService slaPolicyService;

    public AdminSlaPolicyController(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<AdminSlaPolicyResponse>> listPolicies(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        int cappedSize = Math.min(Math.max(size, 1), 50);
        Page<SlaPolicy> resultPage = slaPolicyService.listPolicies(
                PageRequest.of(page, cappedSize,
                        Sort.by(Sort.Direction.ASC, "priority")
                                .and(Sort.by(Sort.Direction.DESC, "effectiveFrom"))));
        List<AdminSlaPolicyResponse> data = resultPage.getContent().stream()
                .map(AdminSlaPolicyResponse::from)
                .toList();
        PageMeta meta = PageMeta.of(page, cappedSize, resultPage.getTotalElements());
        String nextLink = resultPage.hasNext()
                ? "/api/v1/admin/sla-policies?page=" + (page + 1) + "&size=" + cappedSize : null;
        String prevLink = page > 0
                ? "/api/v1/admin/sla-policies?page=" + (page - 1) + "&size=" + cappedSize : null;
        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminSlaPolicyResponse> getPolicy(@PathVariable UUID id) {
        SlaPolicy policy = slaPolicyService.findById(id)
                .orElseThrow(() -> new NotFoundException("SlaPolicy", id.toString()));
        return ResponseEntity.ok(AdminSlaPolicyResponse.from(policy));
    }

    @PostMapping
    public ResponseEntity<AdminSlaPolicyResponse> createPolicy(
            @Valid @RequestBody AdminSlaPolicyRequest request) {
        SlaPolicy created = slaPolicyService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminSlaPolicyResponse.from(created));
    }

    /** Supersede: closes the existing policy row and creates a new version. */
    @PutMapping("/{id}")
    public ResponseEntity<AdminSlaPolicyResponse> supersedePolicy(
            @PathVariable UUID id,
            @Valid @RequestBody AdminSlaPolicyRequest request) {
        SlaPolicy replacement = slaPolicyService.supersede(id, request);
        return ResponseEntity.ok(AdminSlaPolicyResponse.from(replacement));
    }
}
