package com.fieldservice.portal.service;

import com.fieldservice.domain.asset.Asset;
import com.fieldservice.domain.asset.AssetRepository;
import com.fieldservice.domain.site.Site;
import com.fieldservice.domain.site.SiteRepository;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.config.PortalSubmissionProperties;
import com.fieldservice.portal.ratelimit.PortalRateLimiter;
import com.fieldservice.portal.web.dto.CreateServiceRequestRequest;
import com.fieldservice.portal.web.dto.ServiceRequestResponse;
import com.fieldservice.workorder.application.WorkOrderCreateService;
import com.fieldservice.workorder.api.dto.CreateWorkOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles portal service-request submissions (WO-170).
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>Resolves the caller's customer account via {@link CustomerAccessScope} (WO-073 predicate).</li>
 *   <li>Enforces portal-specific rate limit keyed on accountId + clientIp (AC-7).</li>
 *   <li>Validates that the submitted site belongs to that account — returns non-disclosing 404
 *       if not, identical to submitting a random UUID (AC-6).</li>
 *   <li>Validates optional asset is at the submitted site (AC-6).</li>
 *   <li>Delegates creation to {@link WorkOrderCreateService#createForPortal} so the governed
 *       transition table produces state NEW, SLA deadlines are derived, and the Envers revision +
 *       outbox event are written atomically in the same transaction (AC-2, AC-3, AC-4).</li>
 *   <li>Returns a structured response with the work order reference and committed deadlines (AC-1).</li>
 * </ol>
 *
 * <h3>Logging</h3>
 * Actor user ID, account ID, and work order ID are logged at INFO. The free-text
 * {@code faultDescription} is NEVER logged above DEBUG (PII constraint).
 */
@Service
@Transactional
@EnableConfigurationProperties(PortalSubmissionProperties.class)
public class PortalServiceRequestService {

    private static final Logger log = LoggerFactory.getLogger(PortalServiceRequestService.class);

    private final CustomerAccessScope customerAccessScope;
    private final SiteRepository siteRepository;
    private final AssetRepository assetRepository;
    private final WorkOrderCreateService workOrderCreateService;
    private final PortalRateLimiter rateLimiter;
    private final PortalSubmissionProperties props;

    public PortalServiceRequestService(
            CustomerAccessScope customerAccessScope,
            SiteRepository siteRepository,
            AssetRepository assetRepository,
            WorkOrderCreateService workOrderCreateService,
            PortalRateLimiter rateLimiter,
            PortalSubmissionProperties props) {
        this.customerAccessScope    = customerAccessScope;
        this.siteRepository         = siteRepository;
        this.assetRepository        = assetRepository;
        this.workOrderCreateService = workOrderCreateService;
        this.rateLimiter            = rateLimiter;
        this.props                  = props;
    }

    /**
     * Submits a portal service request on behalf of the authenticated customer.
     *
     * @param request  the validated submission payload
     * @param clientIp the resolved client IP address (for rate-limit keying)
     * @return the committed work order reference, state, and SLA deadlines
     * @throws NotFoundException if site or asset does not belong to the caller's account
     * @throws com.fieldservice.sla.SlaPolicyUnavailableException if no active SLA policy
     * @throws com.fieldservice.platform.exception.RateLimitedException if portal limit exceeded
     */
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public ServiceRequestResponse submit(CreateServiceRequestRequest request, String clientIp) {
        UUID accountId = customerAccessScope.resolveAccountId();

        // Rate limit check — keyed on accountId + clientIp (AC-7)
        rateLimiter.checkAndRecord(accountId, clientIp);

        // Validate site ownership — non-disclosing 404 if not owned by this account (AC-6)
        Site site = siteRepository.findById(request.siteId())
                .orElseThrow(() -> new NotFoundException("site", request.siteId()));

        if (!accountId.equals(site.getCustomerId())) {
            throw new NotFoundException("site", request.siteId());
        }

        // Validate optional asset is at the submitted site (AC-6)
        if (request.assetId() != null) {
            Asset asset = assetRepository.findById(request.assetId())
                    .orElseThrow(() -> new NotFoundException("asset", request.assetId()));
            if (!request.siteId().equals(asset.getSiteId())) {
                throw new NotFoundException("asset", request.assetId());
            }
        }

        // Derive a title from faultDescription (truncated for the 500-char title column)
        String title = request.faultDescription().length() > 200
                ? request.faultDescription().substring(0, 200)
                : request.faultDescription();

        CreateWorkOrderRequest createReq = new CreateWorkOrderRequest(
                accountId,
                request.siteId(),
                request.assetId(),
                request.faultDescription(),
                title,
                props.getDefaultPriority(),
                null,
                null
        );

        WorkOrder workOrder = workOrderCreateService.createForPortal(createReq, accountId).workOrder();

        log.info("portal.service_request_submitted: accountId={} workOrderId={} reference={}",
                accountId, workOrder.getId(), workOrder.getReference());

        return new ServiceRequestResponse(
                workOrder.getId(),
                workOrder.getReference(),
                workOrder.getState().name(),
                workOrder.getOrigin().name(),
                workOrder.getResponseDueAt(),
                workOrder.getResolutionDueAt()
        );
    }
}
