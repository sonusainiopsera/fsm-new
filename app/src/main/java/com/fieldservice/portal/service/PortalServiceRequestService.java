package com.fieldservice.portal.service;

import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.ratelimit.PortalRateLimiter;
import com.fieldservice.portal.web.dto.CreateServiceRequestRequest;
import com.fieldservice.portal.web.dto.ServiceRequestResponse;
import com.fieldservice.workorder.application.WorkOrderCreationService;
import com.fieldservice.workorder.web.WorkOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Handles portal service request submission.
 *
 * <p>Security model:
 * <ul>
 *   <li>Method-level {@code @PreAuthorize} restricts to CUSTOMER role.</li>
 *   <li>Site ownership validated via {@link CustomerAccessScope} (predicate-based, not fetch-compare).</li>
 *   <li>Rate limiting enforced per account before delegation to the creation service.</li>
 *   <li>faultDescription is never logged at INFO or above (PII risk).</li>
 * </ul>
 *
 * <p>No repository dependency: all persistence is delegated to
 * {@link WorkOrderCreationService} in the workorder module.
 */
@Service
public class PortalServiceRequestService {

    private static final Logger log = LoggerFactory.getLogger(PortalServiceRequestService.class);

    private final CustomerAccessScope       customerAccessScope;
    private final WorkOrderCreationService  creationService;
    private final PortalRateLimiter         rateLimiter;

    public PortalServiceRequestService(CustomerAccessScope customerAccessScope,
                                        WorkOrderCreationService creationService,
                                        PortalRateLimiter rateLimiter) {
        this.customerAccessScope = customerAccessScope;
        this.creationService     = creationService;
        this.rateLimiter         = rateLimiter;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public ServiceRequestResponse submit(CreateServiceRequestRequest request) {
        // Resolve portal account — throws ScopeUnavailableException (→ 404) if not linked
        UUID accountId = customerAccessScope.resolveAccountId();

        // Rate limit: fail fast before any DB work
        rateLimiter.checkAndRecord(accountId);

        // Resolve authenticated user id for outbox event attribution
        UUID actorUserId = resolveActorUserId();

        // Delegate creation to workorder module's published service
        // Origin=PORTAL stamped inside createFromPortal(); PII faultDescription NOT logged here
        WorkOrderResponse wo = creationService.createFromPortal(
                accountId,
                request.siteId(),
                request.assetId(),
                request.faultDescription(),
                actorUserId);

        log.info("portal_service_request_submitted workOrderId={} reference={} accountId={} actor={} traceId={}",
                wo.id(), wo.reference(), accountId, actorUserId, MDC.get("traceId"));

        return new ServiceRequestResponse(
                wo.id(),
                wo.reference(),
                wo.state().name(),
                wo.origin(),
                wo.responseDueAt(),
                wo.resolutionDueAt());
    }

    private UUID resolveActorUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String subject = jwtAuth.getToken().getSubject();
            if (subject != null) {
                try {
                    return UUID.fromString(subject);
                } catch (IllegalArgumentException ignored) {
                    // fall through to null
                }
            }
        }
        return null;
    }
}
