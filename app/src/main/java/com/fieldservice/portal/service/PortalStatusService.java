package com.fieldservice.portal.service;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.portal.access.CustomerAccessScope;
import com.fieldservice.portal.i18n.CustomerStateLabels;
import com.fieldservice.portal.web.dto.PortalStatusView;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Assembles the customer-facing portal status projection for a work order (WO-171).
 *
 * <h3>Scope enforcement</h3>
 * All queries scope by the caller's customer account via {@link CustomerAccessScope}.
 * A work order not owned by the caller is indistinguishable from one that does not exist.
 *
 * <h3>ETag strategy</h3>
 * {@link #resolveETagOnly(UUID)} executes a lightweight projection query
 * (only {@code version} and {@code updated_at}) so a 304 does not assemble the full
 * projection or run the Envers milestones history query.
 *
 * <h3>Freshness contract</h3>
 * {@code freshness.degraded} is set to {@code true} when a non-critical sub-query
 * (technician name, hold-reason label, Envers milestones) fails.
 * The projection is always returned as 200 with degraded=true rather than 500.
 */
@Service
@Transactional(readOnly = true)
public class PortalStatusService {

    private static final Logger log = LoggerFactory.getLogger(PortalStatusService.class);

    static final int FRESHNESS_STALENESS_SECONDS = 60;

    private final CustomerAccessScope customerAccessScope;
    private final EntityManager entityManager;

    public PortalStatusService(CustomerAccessScope customerAccessScope, EntityManager entityManager) {
        this.customerAccessScope = customerAccessScope;
        this.entityManager = entityManager;
    }

    /**
     * Cheap ETag lookup — executes only a version + updated_at projection query.
     * No Envers reads, no joins to technician or hold_reason.
     *
     * @param workOrderId the work order to check
     * @return strong ETag string (quoted, e.g. {@code "abc123..."})
     * @throws NotFoundException if the work order does not exist or is out of scope
     */
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public String resolveETagOnly(UUID workOrderId) {
        UUID accountId = customerAccessScope.resolveAccountId();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createQuery(
                "SELECT wo.version, wo.updatedAt " +
                "FROM WorkOrder wo JOIN wo.site s " +
                "WHERE wo.id = :id AND s.customerId = :accountId")
                .setParameter("id", workOrderId)
                .setParameter("accountId", accountId)
                .getResultList();

        if (rows.isEmpty()) {
            throw new NotFoundException("work-order", workOrderId);
        }
        Object[] row = rows.get(0);
        int version = ((Number) row[0]).intValue();
        Instant updatedAt = (Instant) row[1];
        return computeETag(workOrderId, version, updatedAt);
    }

    /**
     * Assembles the full portal status projection.
     *
     * <p>Milestones and technician name lookups are each wrapped in a try-catch so a
     * failed sub-query degrades the response ({@code freshness.degraded=true}) rather
     * than returning 500.
     *
     * @param workOrderId the work order to project
     * @return assembled view with ETag computed from the loaded entity
     */
    @PreAuthorize("hasAuthority('CUSTOMER')")
    public PortalStatusResult resolve(UUID workOrderId) {
        UUID accountId = customerAccessScope.resolveAccountId();

        WorkOrder wo = entityManager.createQuery(
                "SELECT wo FROM WorkOrder wo JOIN wo.site s " +
                "WHERE wo.id = :id AND s.customerId = :accountId",
                WorkOrder.class)
                .setParameter("id", workOrderId)
                .setParameter("accountId", accountId)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("work-order", workOrderId));

        Instant observedAt = Instant.now();
        boolean degraded = false;

        // State labels — ON_HOLD gets an enriched description from the hold record
        CustomerStateLabels.StateLabel stateLabel;
        if (wo.getState() == WorkOrderState.ON_HOLD) {
            String holdReasonLabel = loadActiveHoldReasonLabel(workOrderId);
            stateLabel = CustomerStateLabels.forOnHoldWithReason(holdReasonLabel);
        } else {
            stateLabel = CustomerStateLabels.forState(wo.getState());
        }

        // Technician: first name + role label only (no PII beyond policy-permitted fields, AC-3)
        PortalStatusView.TechnicianSummary techSummary = null;
        if (wo.getAssignedTechnicianId() != null) {
            try {
                techSummary = loadTechnicianSummary(wo.getAssignedTechnicianId());
            } catch (Exception e) {
                log.warn("portal.status.technician.error: workOrderId={}", workOrderId, e);
                degraded = true;
            }
        }

        // Milestones from Envers — non-critical; degrade gracefully on failure
        List<PortalStatusView.Milestone> milestones;
        try {
            milestones = loadMilestones(workOrderId);
        } catch (Exception e) {
            log.warn("portal.status.milestones.error: workOrderId={}", workOrderId, e);
            milestones = List.of();
            degraded = true;
        }

        String eTag = computeETag(wo.getId(), wo.getVersion(), wo.getUpdatedAt());

        PortalStatusView view = new PortalStatusView(
                wo.getId(),
                wo.getReference(),
                stateLabel.label(),
                stateLabel.description(),
                wo.getResponseDueAt(),
                wo.getResolutionDueAt(),
                null, // appointmentWindow — no scheduled_from/to column in current schema
                techSummary,
                milestones,
                new PortalStatusView.FreshnessInfo(observedAt, FRESHNESS_STALENESS_SECONDS, degraded)
        );

        return new PortalStatusResult(eTag, view);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private PortalStatusView.TechnicianSummary loadTechnicianSummary(UUID technicianId) {
        String displayName = entityManager.createQuery(
                "SELECT t.displayName FROM Technician t WHERE t.id = :id",
                String.class)
                .setParameter("id", technicianId)
                .getResultStream()
                .findFirst()
                .orElse(null);

        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String firstName = displayName.split("\\s+")[0];
        return new PortalStatusView.TechnicianSummary(firstName, "Technician");
    }

    private String loadActiveHoldReasonLabel(UUID workOrderId) {
        try {
            String reasonCode = entityManager.createQuery(
                    "SELECT woh.reasonCode FROM WorkOrderHold woh " +
                    "WHERE woh.workOrderId = :id AND woh.endedAt IS NULL",
                    String.class)
                    .setParameter("id", workOrderId)
                    .getResultStream()
                    .findFirst()
                    .orElse(null);

            if (reasonCode == null) return null;

            return entityManager.createQuery(
                    "SELECT hr.label FROM HoldReason hr WHERE hr.code = :code",
                    String.class)
                    .setParameter("code", reasonCode)
                    .getResultStream()
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("portal.status.hold_reason.unavailable: workOrderId={}", workOrderId);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<PortalStatusView.Milestone> loadMilestones(UUID workOrderId) {
        AuditReader reader = AuditReaderFactory.get(entityManager);
        List<Object[]> revisions = reader.createQuery()
                .forRevisionsOfEntity(WorkOrder.class, false, true)
                .add(AuditEntity.id().eq(workOrderId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        if (revisions.isEmpty()) {
            return List.of();
        }

        List<PortalStatusView.Milestone> milestones = new ArrayList<>();
        WorkOrder prev = null;

        for (Object[] tuple : revisions) {
            WorkOrder snapshot = (WorkOrder) tuple[0];
            AuditRevisionEntity rev = (AuditRevisionEntity) tuple[1];
            RevisionType revType = (RevisionType) tuple[2];

            String eventType = deriveCustomerEventType(revType, prev, snapshot);
            if (eventType != null) {
                milestones.add(new PortalStatusView.Milestone(
                        rev.getRevisionInstant(),
                        CustomerStateLabels.milestoneLabel(eventType)));
            }
            prev = snapshot;
        }
        return milestones;
    }

    private String deriveCustomerEventType(RevisionType revType, WorkOrder before, WorkOrder after) {
        if (revType == RevisionType.ADD) return "CREATED";
        if (revType == RevisionType.DEL) return "CANCELLED";
        if (after == null) return null;

        WorkOrderState prevState = before != null ? before.getState() : null;
        WorkOrderState newState = after.getState();

        if (!Objects.equals(prevState, newState) && newState != null) {
            return switch (newState) {
                case ASSIGNED    -> "ASSIGNED";
                case EN_ROUTE    -> "DEPARTED";
                case IN_PROGRESS -> (prevState == WorkOrderState.ON_HOLD) ? "RESUMED" : "STARTED";
                case ON_HOLD     -> "HELD";
                case COMPLETED   -> "COMPLETED";
                case CLOSED      -> "CLOSED";
                case CANCELLED   -> "CANCELLED";
                default          -> null;
            };
        }

        // Reassignment — show as neutral "Technician Updated" with no churn detail
        UUID prevTech = before != null ? before.getAssignedTechnicianId() : null;
        UUID newTech = after.getAssignedTechnicianId();
        if (newTech != null && !Objects.equals(prevTech, newTech) && prevTech != null) {
            return "REASSIGNED";
        }
        return null;
    }

    /**
     * Computes a strong ETag from work order identity, version, and last-modified timestamp.
     * The result is a quoted string per HTTP spec: {@code "hex..."}.
     */
    static String computeETag(UUID workOrderId, int version, Instant updatedAt) {
        String raw = workOrderId.toString() + ":" + version + ":" + updatedAt.toEpochMilli();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(hash) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Result pair: strong ETag + assembled view, always consistent with each other. */
    public record PortalStatusResult(String eTag, PortalStatusView view) {}
}
