package com.fieldservice.workorder.audit;

import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.workorder.domain.WorkOrder;
import org.hibernate.envers.RevisionType;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing a single revision snapshot of a {@link WorkOrder}.
 *
 * <p>Contains revision metadata (who, when, why) plus a shallow snapshot of the
 * entity state at that revision. Password-classified fields are never included.
 */
public record RevisionEntry(
        int        revisionId,
        Instant    revisionTimestamp,
        String     revisionType,
        String     actorUserId,
        String     actorRole,
        String     traceId,
        String     clientIp,
        UUID       workOrderId,
        String     reference,
        String     state,
        String     priority,
        UUID       siteId,
        UUID       assignedTechnicianId,
        String     description
) {

    static RevisionEntry from(WorkOrder wo, AppRevision rev, RevisionType type) {
        return new RevisionEntry(
                rev.getId(),
                rev.getRevisionInstant(),
                type.name(),
                rev.getActorUserId(),
                rev.getActorRole(),
                rev.getTraceId(),
                rev.getClientIp(),
                wo.getId(),
                wo.getReference(),
                wo.getState() != null ? wo.getState().name() : null,
                wo.getPriority(),
                wo.getSite() != null ? wo.getSite().getId() : null,
                wo.getAssignedTechnicianId(),
                wo.getDescription()
        );
    }
}
