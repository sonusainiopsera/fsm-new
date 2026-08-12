package com.fieldservice.portal.service;

import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.access.ScopeUnavailableException;
import com.fieldservice.portal.i18n.CustomerStateLabels;
import com.fieldservice.portal.web.dto.PortalStatusView;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.repository.TechnicianRepository;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.holds.WorkOrderHold;
import com.fieldservice.workorder.holds.WorkOrderHoldRepository;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Portal-facing read service for work order status projections.
 *
 * <p>Access model:
 * <ul>
 *   <li>Account ownership is enforced via a predicate combining the work order id with
 *       the portal account's customer id — a foreign work order id is indistinguishable
 *       from a non-existent one (both throw {@link ScopeUnavailableException} → 404).</li>
 *   <li>No GPS position, full technician identity, internal codes, or other-account data
 *       ever appears in the projection.</li>
 * </ul>
 *
 * <p>ETag contract: the version string changes on every entity write, so the ETag
 * changes whenever the work order state, deadlines, or assignment changes.
 *
 * <p>Freshness: {@code degraded=true} is set when the Envers timeline query fails —
 * the status projection is still served rather than returning 500.
 */
@Service
public class PortalStatusService {

    private static final Logger log = LoggerFactory.getLogger(PortalStatusService.class);

    private final WorkOrderRepository   workOrderRepository;
    private final WorkOrderHoldRepository holdRepository;
    private final TechnicianRepository  technicianRepository;
    private final CustomerAccessScope   customerAccessScope;
    private final EntityManager         entityManager;
    private final Timer                 peekTimer;
    private final Timer                 fullTimer;

    public PortalStatusService(
            WorkOrderRepository   workOrderRepository,
            WorkOrderHoldRepository holdRepository,
            TechnicianRepository  technicianRepository,
            CustomerAccessScope   customerAccessScope,
            EntityManager         entityManager,
            MeterRegistry         meterRegistry) {
        this.workOrderRepository  = workOrderRepository;
        this.holdRepository       = holdRepository;
        this.technicianRepository = technicianRepository;
        this.customerAccessScope  = customerAccessScope;
        this.entityManager        = entityManager;
        this.peekTimer  = Timer.builder("portal.status.etag.check.duration")
                .description("Time to resolve the ETag for a portal status request")
                .register(meterRegistry);
        this.fullTimer  = Timer.builder("portal.status.full.duration")
                .description("Time to assemble the full portal status projection")
                .register(meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Lightweight path: resolves the current ETag for the given work order.
     *
     * <p>Loads only the {@link WorkOrder} entity (no timeline, no technician lookup),
     * making this suitable for the 304 fast path. Returns empty when the work order
     * does not exist or is not accessible to the authenticated portal account.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Optional<String> peekEtag(UUID workOrderId) {
        Timer.Sample sample = Timer.start();
        try {
            UUID accountId = customerAccessScope.resolveAccountId();
            return workOrderRepository.findOne(scopedSpec(workOrderId, accountId))
                    .map(wo -> etag(wo.getId(), wo.getVersion()));
        } finally {
            sample.stop(peekTimer);
        }
    }

    /**
     * Full projection path: assembles the complete {@link PortalStatusView} along with
     * the ETag derived from the entity loaded during this call.
     *
     * <p>Returning the ETag from the same entity load that produced the body guarantees
     * that the ETag always matches the response body, even when a concurrent transition
     * occurs between a prior {@link #peekEtag(UUID)} call and this call.
     *
     * <p>The returned view's {@code freshness.degraded} flag is set when the Envers
     * timeline query fails; the status is still served (degraded-graceful, not 500).
     *
     * @throws ScopeUnavailableException when the work order is not found or not accessible
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public StatusResult getFullStatus(UUID workOrderId) {
        Timer.Sample sample = Timer.start();
        try {
            return buildFullStatus(workOrderId);
        } finally {
            sample.stop(fullTimer);
        }
    }

    /** Pairs a strong ETag with the view projection assembled from the same entity load. */
    public record StatusResult(String etag, PortalStatusView view) {}

    // -------------------------------------------------------------------------
    // ETag helpers (public static so controller can compute without service call)
    // -------------------------------------------------------------------------

    /**
     * Computes the ETag string for a given work order identity and version.
     * Format: {@code "workOrderId:version"} (RFC 7232 strong ETag — includes outer quotes).
     */
    public static String etag(UUID workOrderId, int version) {
        return '"' + workOrderId.toString() + ':' + version + '"';
    }

    // -------------------------------------------------------------------------
    // Private implementation
    // -------------------------------------------------------------------------

    private StatusResult buildFullStatus(UUID workOrderId) {
        UUID accountId = customerAccessScope.resolveAccountId();

        WorkOrder wo = workOrderRepository.findOne(scopedSpec(workOrderId, accountId))
                .orElseThrow(() -> new ScopeUnavailableException(
                        "Work order not found or not accessible to this portal account"));

        // Hold reason code for ON_HOLD state
        String holdReasonCode = null;
        if (wo.getState() == WorkOrderStatus.ON_HOLD) {
            holdReasonCode = holdRepository
                    .findByWorkOrderIdAndEndedAtIsNull(wo.getId())
                    .map(WorkOrderHold::getReasonCode)
                    .orElse(null);
        }

        CustomerStateLabels labels = CustomerStateLabels.resolve(wo.getState(), holdReasonCode);

        // Technician summary — first name and role label only (never full name, phone, email, id)
        PortalStatusView.TechnicianSummary techSummary = null;
        if (wo.getAssignedTechnicianId() != null) {
            techSummary = technicianRepository.findById(wo.getAssignedTechnicianId())
                    .map(this::buildTechnicianSummary)
                    .orElse(null);
        }

        // Milestones from Envers transition history
        boolean degraded = false;
        List<PortalStatusView.PortalMilestone> milestones;
        try {
            milestones = buildMilestones(workOrderId);
        } catch (Exception ex) {
            log.warn("portal_milestone_build_failed workOrderId={} reason={}", workOrderId, ex.getMessage());
            milestones = List.of();
            degraded = true;
        }

        Instant now = Instant.now();

        String computedEtag = etag(wo.getId(), wo.getVersion());

        PortalStatusView view = new PortalStatusView(
                wo.getId(),
                wo.getReference(),
                labels.getLabel(),
                labels.getDescription(),
                wo.getResponseDeadline(),
                wo.getResolutionDeadline(),
                null,          // appointmentWindow — not yet modelled in the domain
                techSummary,
                milestones,
                new PortalStatusView.Freshness(now, 60, degraded));

        return new StatusResult(computedEtag, view);
    }

    private PortalStatusView.TechnicianSummary buildTechnicianSummary(Technician t) {
        String display = t.getDisplayName() != null ? t.getDisplayName() : t.getFullName();
        String firstName = null;
        if (display != null && !display.isBlank()) {
            String[] parts = display.trim().split("\\s+", 2);
            firstName = parts[0];
        }
        return new PortalStatusView.TechnicianSummary(firstName, "Field Engineer");
    }

    @SuppressWarnings("unchecked")
    private List<PortalStatusView.PortalMilestone> buildMilestones(UUID workOrderId) {
        var reader = AuditReaderFactory.get(entityManager);
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(org.hibernate.envers.query.AuditEntity.id().eq(workOrderId))
                .addOrder(org.hibernate.envers.query.AuditEntity.revisionNumber().asc())
                .getResultList();

        List<PortalStatusView.PortalMilestone> milestones = new ArrayList<>(rows.size());
        WorkOrder prev = null;
        for (Object[] row : rows) {
            WorkOrder   current  = (WorkOrder)    row[0];
            AppRevision rev      = (AppRevision)  row[1];
            RevisionType type    = (RevisionType) row[2];

            if (type == RevisionType.ADD) {
                milestones.add(new PortalStatusView.PortalMilestone(
                        rev.getRevisionInstant(), "Service request received"));
            } else if (type == RevisionType.MOD && prev != null
                    && current.getState() != prev.getState()) {
                CustomerStateLabels label = CustomerStateLabels.resolve(current.getState(), null);
                milestones.add(new PortalStatusView.PortalMilestone(
                        rev.getRevisionInstant(), label.getLabel()));
            }
            prev = current;
        }
        return milestones;
    }

    private Specification<WorkOrder> scopedSpec(UUID workOrderId, UUID accountId) {
        return (root, q, cb) -> cb.and(
                cb.equal(root.get("id"), workOrderId),
                cb.equal(root.join("site").get("customerId"), accountId));
    }

    private UUID resolveActorUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String subject = jwtAuth.getToken().getSubject();
            if (subject != null) {
                try {
                    return UUID.fromString(subject);
                } catch (IllegalArgumentException ignored) {
                    // non-UUID subject — fall through
                }
            }
        }
        return null;
    }
}
