package com.fieldservice.workforce.web;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.security.AccessScopeResolver;
import com.fieldservice.workforce.application.PositionReportException;
import com.fieldservice.workforce.application.PositionReportingService;
import com.fieldservice.workforce.web.dto.PositionUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Purpose-limited position reporting endpoint for field technicians (WO-159).
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Identity resolved exclusively from the JWT — no client-supplied technician ID.</li>
 *   <li>202 Accepted on success; 422 when purpose limitation fails; 429 when rate-limited.</li>
 *   <li>Coordinates are never written to response bodies, logs, or error payloads.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/technicians/me/position")
@PreAuthorize("hasRole('TECHNICIAN')")
public class TechnicianPositionController {

    private static final long RATE_LIMIT_RETRY_AFTER_SECONDS = 30L;

    private final PositionReportingService positionReportingService;
    private final AccessScopeResolver      scopeResolver;

    public TechnicianPositionController(PositionReportingService positionReportingService,
                                        AccessScopeResolver scopeResolver) {
        this.positionReportingService = positionReportingService;
        this.scopeResolver            = scopeResolver;
    }

    /**
     * Reports the technician's current position.
     * Returns 202 on success, 422 if purpose limitation is not satisfied,
     * 429 if rate-limited, and 400 for stale timestamps (in addition to standard
     * bean-validation 400s for invalid coordinates).
     */
    @PostMapping
    public ResponseEntity<?> reportPosition(@Valid @RequestBody PositionUpdateRequest req,
                                            HttpServletRequest httpRequest) {
        UUID technicianId = scopeResolver.resolve().technicianId();
        try {
            positionReportingService.report(technicianId, req);
            return ResponseEntity.accepted().build();
        } catch (PositionReportException ex) {
            return switch (ex.getReason()) {
                case STALE_TIMESTAMP -> ResponseEntity
                        .badRequest()
                        .body(ErrorEnvelope.of("STALE_TIMESTAMP", ex.getMessage(), List.of(),
                                traceId(httpRequest)));

                case NO_ACTIVE_JOB -> ResponseEntity
                        .unprocessableEntity()
                        .body(ErrorEnvelope.of("NO_ACTIVE_JOB", ex.getMessage(), List.of(),
                                traceId(httpRequest)));

                case RATE_LIMITED -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(RATE_LIMIT_RETRY_AFTER_SECONDS));
                    yield ResponseEntity
                            .status(HttpStatus.TOO_MANY_REQUESTS)
                            .headers(headers)
                            .body(ErrorEnvelope.of("RATE_LIMITED", ex.getMessage(), List.of(),
                                    traceId(httpRequest)));
                }
            };
        }
    }

    private static String traceId(HttpServletRequest req) {
        String id = (String) req.getAttribute("traceId");
        return id != null ? id : "unknown";
    }
}
