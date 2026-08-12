package com.fieldservice.dispatch.api;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * Public port for the dispatch assignment operation (WO-138).
 *
 * <p>A single call atomically:
 * <ol>
 *   <li>Re-evaluates the certification hard guard for the chosen technician.</li>
 *   <li>Computes the override flag from the referenced recommendation snapshot.</li>
 *   <li>Transitions the work order {@code NEW → ASSIGNED}.</li>
 *   <li>Persists the {@code assignment} row.</li>
 *   <li>Publishes the {@code TechnicianAssigned} outbox event.</li>
 * </ol>
 *
 * <p>All five writes share one {@link org.springframework.transaction.annotation.Transactional}
 * boundary: an outbox row cannot exist without its assignment row, and vice versa.
 */
public interface AssignmentService {

    /**
     * Assigns {@code technicianId} to {@code workOrderId}.
     *
     * @param workOrderId               the work order to assign
     * @param technicianId              the technician to assign to the work order
     * @param assignedBy                authenticated dispatcher/admin user ID
     * @param recommendationSnapshotId  optional snapshot to derive rank and override flag from
     * @param overrideReason            free text; required when rank is absent or &gt; 3
     * @param expectedVersion           optimistic-lock version the caller observed
     * @return assignment result with the created assignment details and any parts warning
     * @throws CertificationGuardException       if the technician lacks a current required cert (422)
     * @throws OverrideReasonRequiredException   if override reason is absent when required (400)
     * @throws EligibilityDataException          if eligibility data cannot be loaded (503, fail-safe)
     */
    AssignmentResult assign(UUID workOrderId, UUID technicianId, UUID assignedBy,
                            @Nullable UUID recommendationSnapshotId,
                            @Nullable String overrideReason,
                            int expectedVersion);

    // ── Result and exception types ────────────────────────────────────────────

    /**
     * Successful assignment result.
     *
     * @param assignmentId         the newly created assignment row ID
     * @param workOrderId          the work order
     * @param technicianId         the assigned technician
     * @param state                the new work order state (ASSIGNED)
     * @param assignedAt           assignment timestamp
     * @param recommendationRank   rank in the snapshot; null when override or no snapshot
     * @param recommendationScore  composite score; null when override or no snapshot
     * @param overrideRecorded     true when an override reason was persisted
     * @param snapshotStale        true when the snapshot was older than the staleness window
     * @param partsWarning         advisory parts shortage warning; null when no warning
     */
    record AssignmentResult(
            UUID assignmentId,
            UUID workOrderId,
            UUID technicianId,
            WorkOrderState state,
            Instant assignedAt,
            Integer recommendationRank,
            Double recommendationScore,
            boolean overrideRecorded,
            boolean snapshotStale,
            @Nullable String partsWarning
    ) {}

    /** Thrown when the technician lacks a current required certification. */
    class CertificationGuardException extends RuntimeException {
        private final String code;
        public CertificationGuardException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }

    /** Thrown when an override reason is required but not supplied. */
    class OverrideReasonRequiredException extends RuntimeException {
        public OverrideReasonRequiredException(String message) { super(message); }
    }
}
