package com.fieldservice.sla.web;

import com.fieldservice.domain.sla.SlaPolicy;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.sla.internal.SlaPolicyService;
import com.fieldservice.sla.web.dto.CreateSlaPolicyRequest;
import com.fieldservice.sla.web.dto.SlaPolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only REST endpoints for managing SLA policies.
 *
 * <p>All operations require the {@code ADMIN} authority. Any non-ADMIN token
 * (DISPATCHER, TECHNICIAN, MANAGER, CUSTOMER) receives 403 with no existence disclosure.
 *
 * <p>PUT supersedes the named row rather than mutating it: the prior row is closed
 * ({@code effective_to = newPolicy.effectiveFrom}, {@code active = false}) and a new
 * row is inserted, preserving the full history of policy changes.
 */
@RestController
@RequestMapping("/api/v1/admin/sla-policies")
@Tag(name = "Admin SLA Policies", description = "Admin-only SLA policy configuration")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminSlaPolicyController {

    private final SlaPolicyService slaPolicyService;

    public AdminSlaPolicyController(SlaPolicyService slaPolicyService) {
        this.slaPolicyService = slaPolicyService;
    }

    @Operation(operationId = "listSlaPolicies", summary = "List all SLA policies (ADMIN)")
    @GetMapping
    public ResponseEntity<List<SlaPolicyResponse>> list() {
        List<SlaPolicyResponse> responses = slaPolicyService.findAll().stream()
                .map(SlaPolicyResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Operation(operationId = "createSlaPolicy", summary = "Create a new SLA policy (ADMIN)")
    @PostMapping
    public ResponseEntity<SlaPolicyResponse> create(
            @Valid @RequestBody CreateSlaPolicyRequest req,
            HttpServletRequest httpRequest,
            UriComponentsBuilder uriBuilder) {

        validateResolutionGteResponse(req);

        SlaPolicy entity = buildEntity(req);
        SlaPolicy saved = slaPolicyService.createPolicy(entity);

        var location = uriBuilder.path("/api/v1/admin/sla-policies/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(SlaPolicyResponse.from(saved));
    }

    @Operation(operationId = "updateSlaPolicy", summary = "Supersede an SLA policy (ADMIN)")
    @PutMapping("/{id}")
    public ResponseEntity<SlaPolicyResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateSlaPolicyRequest req) {

        validateResolutionGteResponse(req);

        SlaPolicy newEntity = buildEntity(req);
        SlaPolicy saved = slaPolicyService.supersede(id, newEntity, req.effectiveFrom());
        return ResponseEntity.ok(SlaPolicyResponse.from(saved));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void validateResolutionGteResponse(CreateSlaPolicyRequest req) {
        if (req.responseMinutes() != null && req.resolutionMinutes() != null
                && req.resolutionMinutes() < req.responseMinutes()) {
            throw new jakarta.validation.ConstraintViolationException(
                    "resolutionMinutes must be >= responseMinutes",
                    java.util.Set.of());
        }
    }

    private static SlaPolicy buildEntity(CreateSlaPolicyRequest req) {
        SlaPolicy entity = new SlaPolicy();
        entity.setPriority(req.priority());
        entity.setResponseMinutes(req.responseMinutes());
        entity.setResolutionMinutes(req.resolutionMinutes());
        entity.setAtRiskFraction(req.atRiskFraction());
        entity.setEffectiveFrom(req.effectiveFrom());
        return entity;
    }
}
