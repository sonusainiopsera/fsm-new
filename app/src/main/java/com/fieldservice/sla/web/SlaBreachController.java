package com.fieldservice.sla.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import com.fieldservice.sla.SlaBreachReasonCode;
import com.fieldservice.sla.internal.SlaBreachEntity;
import com.fieldservice.sla.internal.SlaBreachService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * SLA breach read and attribution endpoints.
 *
 * <p>All endpoints require DISPATCHER, MANAGER, or ADMIN role; TECHNICIAN and CUSTOMER
 * receive 403 via {@code @PreAuthorize} before any query runs, disclosing no existence
 * information about the breach record.
 *
 * <h3>Attribution idempotency</h3>
 * Re-attributing a reason code creates a new Envers revision while keeping the previous
 * value recoverable with actor and timestamp. The platform {@code IdempotencyFilter} handles
 * duplicate POST requests transparently via {@code Idempotency-Key} header.
 *
 * <h3>No delete endpoint</h3>
 * There is no HTTP endpoint to delete a breach — records are append-and-revise only.
 */
@RestController
@RequestMapping("/api/v1/sla/breaches")
public class SlaBreachController {

    private static final int MAX_PAGE_SIZE = 50;

    private final SlaBreachService breachService;

    public SlaBreachController(SlaBreachService breachService) {
        this.breachService = breachService;
    }

    // ─── GET /api/v1/sla/breaches ────────────────────────────────────────────

    /**
     * Paginated, filtered breach list.
     *
     * <p>Filtering applies before pagination. Sort is fixed to
     * {@code detected_at DESC, id ASC} (deterministic). Page size is server-enforced
     * at a maximum of 50.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code page} — 0-indexed page number (default 0)</li>
     *   <li>{@code size} — page size, max 50 (default 20)</li>
     *   <li>{@code breachType} — RESPONSE or RESOLUTION</li>
     *   <li>{@code unattributed} — true = only records awaiting a reason code</li>
     *   <li>{@code reasonCode} — filter by specific reason code</li>
     *   <li>{@code priority} — filter by work order priority (e.g. P1, P2)</li>
     *   <li>{@code from} — lower bound on detectedAt (ISO-8601 instant, inclusive)</li>
     *   <li>{@code to} — upper bound on detectedAt (ISO-8601 instant, inclusive)</li>
     * </ul>
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN')")
    public PagedResponse<SlaBreachResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String breachType,
            @RequestParam(defaultValue = "false") boolean unattributed,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        int cappedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(0, page), cappedSize);

        Page<SlaBreachEntity> breachPage = breachService.list(
                breachType, unattributed, reasonCode, priority, from, to, pageable);

        List<SlaBreachResponse> items = breachPage.getContent().stream()
                .map(SlaBreachResponse::from)
                .toList();

        PageMeta meta = PageMeta.of(breachPage.getNumber(), breachPage.getSize(),
                breachPage.getTotalElements());
        PageLinks links = new PageLinks(null, null);

        return new PagedResponse<>(items, meta, links);
    }

    // ─── POST /api/v1/sla/breaches/{id}/reason ──────────────────────────────

    /**
     * Attributes a reason code to a breach.
     *
     * <p>Accepts {@code Idempotency-Key} header (handled transparently by the platform
     * idempotency filter). Returns 200 with the updated record.
     *
     * <p>Unknown reason codes are rejected with 400 (Bean Validation on the enum field).
     * A non-existent or out-of-scope breach returns 403 without disclosing existence.
     * Concurrent attributions return 409 (optimistic lock on {@code version} field).
     */
    @PostMapping("/{id}/reason")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<SlaBreachResponse> attribute(
            @PathVariable UUID id,
            @Valid @RequestBody SlaBreachAttributeRequest request,
            Authentication authentication) {

        UUID actorId = resolveActorId(authentication);

        SlaBreachEntity updated = breachService
                .attribute(id, request.reasonCode(), request.reasonNote(), actorId)
                .orElseThrow(() -> new ScopedAccessDeniedException(
                        "sla_breach", "Breach not found or outside caller scope"));

        return ResponseEntity.ok(SlaBreachResponse.from(updated));
    }

    // ─── GET /api/v1/sla/breaches/reason-codes ──────────────────────────────

    /** Returns the full controlled vocabulary so clients render exactly the allowed options. */
    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'MANAGER', 'ADMIN')")
    public List<String> reasonCodes() {
        return Arrays.stream(SlaBreachReasonCode.values())
                .map(Enum::name)
                .toList();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static UUID resolveActorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            // Subject is not a UUID (e.g., email-based test token) — use null
            return null;
        }
    }
}
