package com.fieldservice.portal.web;

import com.fieldservice.portal.service.PortalServiceRequestService;
import com.fieldservice.portal.web.dto.CreateServiceRequestRequest;
import com.fieldservice.portal.web.dto.ServiceRequestResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Portal service-request submission endpoint (WO-170).
 *
 * <p>POST /api/v1/portal/service-requests — restricted to the CUSTOMER role.
 *
 * <h3>Rate limiting</h3>
 * Every request is checked against the portal-specific rate limit before the
 * service layer is invoked. Rate limiting is keyed on account ID + client IP
 * so neither a compromised account nor a shared NAT address can weaponise
 * the other dimension. Exceeded limit → 429 with Retry-After (AC-7).
 *
 * <h3>Idempotency</h3>
 * The platform {@link com.fieldservice.idempotency.IdempotencyKeyFilter} intercepts
 * all POST requests when the api profile is active. Supplying the same
 * {@code Idempotency-Key} within 24 hours returns the original 201 response.
 * A differing body with the same key returns 409 (AC-8).
 *
 * <h3>Logging</h3>
 * Actor user ID, account ID, work order ID, and traceId are logged.
 * The free-text fault description is excluded from all log lines above DEBUG (PII).
 */
@RestController
@RequestMapping("/api/v1/portal/service-requests")
@PreAuthorize("hasAuthority('CUSTOMER')")
public class PortalServiceRequestController {

    private static final Logger log = LoggerFactory.getLogger(PortalServiceRequestController.class);

    private final PortalServiceRequestService service;

    public PortalServiceRequestController(PortalServiceRequestService service) {
        this.service = service;
    }

    /**
     * Submits a portal service request creating a work order in state NEW.
     *
     * @param request     the validated submission payload
     * @param httpRequest the raw servlet request (used to extract client IP for rate limiting)
     * @return 201 Created with {@link ServiceRequestResponse} wrapped in a {@code data} envelope
     */
    @PostMapping
    public ResponseEntity<Map<String, ServiceRequestResponse>> submit(
            @RequestBody @Valid CreateServiceRequestRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = resolveClientIp(httpRequest);
        log.debug("portal.submit_received: ip={} traceId={}", clientIp, MDC.get("traceId"));

        ServiceRequestResponse response = service.submit(request, clientIp);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(Map.of("data", response));
    }

    private static String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = req.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return req.getRemoteAddr() != null ? req.getRemoteAddr() : "unknown";
    }
}
