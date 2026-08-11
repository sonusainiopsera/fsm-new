package com.fieldservice.privacy.web;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.privacy.api.ClassificationAdminPort;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.UpdateClassificationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin API for reviewing and adjusting data classification tiers.
 *
 * <p>Restricted to the {@code PRIVACY_ADMIN} and {@code ADMIN} roles via
 * {@code @PreAuthorize} on the delegated service methods. This controller never
 * references the internal repository or entity — all access goes through
 * {@link ClassificationAdminPort}.
 *
 * <p>Errors use the shared structured error contract
 * {@code {code, message, fieldErrors[], traceId}} with no stack traces (A10).
 */
@RestController
@RequestMapping("/api/v1/privacy/classifications")
@Tag(name = "Privacy - Classifications", description = "Data classification registry admin API")
public class ClassificationController {

    private final ClassificationAdminPort adminPort;

    public ClassificationController(ClassificationAdminPort adminPort) {
        this.adminPort = adminPort;
    }

    @Operation(
            operationId = "listClassifications",
            summary = "List data classification rows (paginated, max size 50)"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<ClassificationView>> listClassifications(
            @RequestParam(required = false) ClassificationTier tier,
            PageQuery pageQuery,
            HttpServletRequest request) {
        return ResponseEntity.ok(adminPort.listClassifications(tier, pageQuery, request));
    }

    @Operation(
            operationId = "updateClassification",
            summary = "Update tier and handling notes for a classification row"
    )
    @PutMapping(value = "/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClassificationView> updateClassification(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateClassificationRequest body) {
        return ResponseEntity.ok(adminPort.updateClassification(id, body));
    }
}
