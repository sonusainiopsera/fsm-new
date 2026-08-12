package com.fieldservice.privacy.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.privacy.api.DsarRequestService;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarRequestView;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.ExportArtifactView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for Data Subject Access Request (DSAR) lifecycle management.
 *
 * <p>POST /api/v1/privacy/dsar-requests                  — intake<br>
 * GET  /api/v1/privacy/dsar-requests                  — paginated queue<br>
 * GET  /api/v1/privacy/dsar-requests/{id}             — single request<br>
 * POST /api/v1/privacy/dsar-requests/{id}/transitions — lifecycle event<br>
 * GET  /api/v1/privacy/dsar-requests/{id}/export      — download artifact<br>
 * GET  /api/v1/privacy/dsar-metrics                   — fulfilment metric
 */
@RestController
@RequestMapping("/api/v1/privacy")
public class DsarRequestController {

    private static final int MAX_PAGE_SIZE = 50;

    private static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            Map.of(
                    "submittedAt",  "submittedAt",
                    "dueAt",        "dueAt",
                    "state",        "state",
                    "requestType",  "requestType",
                    "id",           "id"
            ),
            "dueAt"
    );

    private final DsarRequestService service;

    public DsarRequestController(DsarRequestService service) {
        this.service = service;
    }

    @PostMapping("/dsar-requests")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<DsarRequestResponse> create(
            @Valid @RequestBody CreateDsarRequest request,
            Authentication authentication) {

        String actor = actorName(authentication);
        DsarRequestView view = service.createRequest(
                request.requestType(), request.subjectType(),
                request.subjectId(), request.notes(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(DsarRequestResponse.from(view));
    }

    @GetMapping("/dsar-requests")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<PagedResponse<DsarRequestResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) DsarState state) {

        int clampedSize = Math.min(size, MAX_PAGE_SIZE);
        PageQuery query    = PageQuery.of(page, clampedSize, sort);
        Pageable  pageable = query.toPageable(SORT_ALLOW_LIST);

        Page<DsarRequestView> result = service.listRequests(state, pageable);

        PageMeta  meta  = PageMeta.of(result.getNumber(), result.getSize(), result.getTotalElements());
        PageLinks links = PageLinks.none();

        return ResponseEntity.ok(PagedResponse.of(
                result.getContent().stream().map(DsarRequestResponse::from).toList(),
                meta, links));
    }

    @GetMapping("/dsar-requests/{id}")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<DsarRequestResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(DsarRequestResponse.from(service.getRequest(id)));
    }

    @PostMapping("/dsar-requests/{id}/transitions")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<DsarRequestResponse> transition(
            @PathVariable UUID id,
            @Valid @RequestBody DsarTransitionRequest request,
            Authentication authentication) {

        String actor = actorName(authentication);
        DsarRequestView view = service.applyTransition(
                id, request.event(), request.verificationMethod(),
                request.note(), request.version(), actor);
        return ResponseEntity.ok(DsarRequestResponse.from(view));
    }

    @GetMapping("/dsar-requests/{id}/export")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<ExportArtifactResponse> getExport(
            @PathVariable UUID id,
            Authentication authentication) {

        String actor = actorName(authentication);
        ExportArtifactView view = service.getExport(id, actor);
        return ResponseEntity.ok(ExportArtifactResponse.from(view));
    }

    @GetMapping("/dsar-metrics")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<DsarRequestService.FulfilmentMetric> getMetrics() {
        return ResponseEntity.ok(service.getFulfilmentMetric());
    }

    private static String actorName(Authentication auth) {
        return (auth != null && auth.getName() != null) ? auth.getName() : "SYSTEM";
    }
}
