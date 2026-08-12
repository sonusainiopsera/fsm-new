package com.fieldservice.sla.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.sla.SlaBreachAdminService;
import com.fieldservice.sla.SlaBreachDto;
import com.fieldservice.sla.web.dto.AttributeReasonRequest;
import com.fieldservice.sla.web.dto.SlaBreachResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for querying and attributing SLA breach records.
 *
 * <p>All operations require DISPATCHER, MANAGER or ADMIN authority.
 * TECHNICIAN and CUSTOMER receive 403 without existence disclosure
 * (enforced by Spring Security before any DB access).
 */
@RestController
@RequestMapping("/api/v1/sla/breaches")
@Tag(name = "SLA Breaches", description = "SLA breach records and reason attribution")
@PreAuthorize("hasAnyAuthority('DISPATCHER','MANAGER','ADMIN')")
public class SlaBreachController {

    static final int MAX_PAGE_SIZE = 50;

    private final SlaBreachAdminService slaBreachAdminService;

    public SlaBreachController(SlaBreachAdminService slaBreachAdminService) {
        this.slaBreachAdminService = slaBreachAdminService;
    }

    @Operation(operationId = "listSlaBreaches",
               summary = "List SLA breaches with filtering and pagination")
    @GetMapping
    public ResponseEntity<PagedResponse<SlaBreachResponse>> list(
            @RequestParam(required = false) String breachType,
            @RequestParam(required = false) Boolean unattributed,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "detectedAt") String sort,
            @RequestParam(defaultValue = "true") boolean asc,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);

        List<SlaBreachDto> items = slaBreachAdminService.listBreaches(
                breachType, unattributed, from, to, sort, asc, page, effectiveSize);
        long total = slaBreachAdminService.countBreaches(breachType, unattributed, from, to);

        List<SlaBreachResponse> data = items.stream().map(SlaBreachResponse::from).toList();
        PageMeta  meta  = PageMeta.of(page, effectiveSize, total);
        PageLinks links = PageLinks.none();

        return ResponseEntity.ok(PagedResponse.of(data, meta, links));
    }

    @Operation(operationId = "attributeSlaBreachReason",
               summary = "Attribute a reason code to an SLA breach")
    @PostMapping("/{id}/reason")
    public ResponseEntity<SlaBreachResponse> attributeReason(
            @PathVariable UUID id,
            @Valid @RequestBody AttributeReasonRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        UUID actorId = jwt != null ? UUID.fromString(jwt.getSubject()) : null;
        Instant now  = Instant.now();

        SlaBreachDto updated = slaBreachAdminService.attributeReason(
                id, req.reasonCode(), req.note(), actorId, now);

        return ResponseEntity.ok(SlaBreachResponse.from(updated));
    }
}
