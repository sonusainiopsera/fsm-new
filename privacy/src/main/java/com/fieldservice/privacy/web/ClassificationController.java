package com.fieldservice.privacy.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.privacy.api.ClassificationService;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Admin API for the data classification registry.
 *
 * <p>Both endpoints are restricted to {@code PRIVACY_ADMIN} and {@code ADMIN} roles.
 * A non-privacy role receives 403 with no existence disclosure.
 */
@RestController
@RequestMapping("/api/v1/privacy/classifications")
public class ClassificationController {

    private static final SortAllowList SORT_ALLOW_LIST = SortAllowList.of(
            Map.of(
                    "entityName", "entityName",
                    "fieldName",  "fieldName",
                    "tier",       "tier",
                    "module",     "module",
                    "updatedAt",  "updatedAt"
            ),
            "entityName"
    );

    private final ClassificationService service;

    public ClassificationController(ClassificationService service) {
        this.service = service;
    }

    /**
     * Returns a paginated list of classification rows.
     *
     * <p>Size is server-enforced to ≤ 50. Sort is validated against the allow-list;
     * an unknown sort field produces 400.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<PagedResponse<ClassificationResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) ClassificationTier tier) {

        PageQuery query    = PageQuery.of(page, size, sort);
        Pageable  pageable = query.toPageable(SORT_ALLOW_LIST);

        Page<ClassificationView> result = service.listPage(pageable, tier);

        PageMeta  meta  = PageMeta.of(result.getNumber(), result.getSize(), result.getTotalElements());
        PageLinks links = PageLinks.none();

        PagedResponse<ClassificationResponse> response = PagedResponse.of(
                result.getContent().stream().map(ClassificationResponse::from).toList(),
                meta,
                links);

        return ResponseEntity.ok(response);
    }

    /**
     * Updates the tier, lawful-basis note and handling notes for an existing registry row.
     *
     * <p>Returns 400 on an invalid tier value, 404 if the id is unknown, 409 on a stale version.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PRIVACY_ADMIN', 'ADMIN')")
    public ResponseEntity<ClassificationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ClassificationRequest request,
            Authentication authentication) {

        String actor = authentication != null ? authentication.getName() : "SYSTEM";

        ClassificationView updated = service.update(
                id,
                request.tier(),
                request.lawfulBasisNote(),
                request.handlingNotes(),
                request.version(),
                actor);

        return ResponseEntity.ok(ClassificationResponse.from(updated));
    }
}
