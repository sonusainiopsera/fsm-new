package com.fieldservice.dispatch.api;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public port for the reassignment operation (WO-139).
 *
 * <p>A single call atomically:
 * <ol>
 *   <li>Validates state eligibility (ASSIGNED, EN_ROUTE, ON_HOLD, IN_PROGRESS → 409 otherwise).</li>
 *   <li>Rejects same-technician no-op (→ 400).</li>
 *   <li>Re-evaluates the certification hard guard for the incoming technician (→ 422).</li>
 *   <li>Evaluates the appointment-pinning guard (→ 422 if unacknowledged breach).</li>
 *   <li>Supersedes the active assignment (sets end_at, superseded_by, reassignment_reason).</li>
 *   <li>Creates the new assignment row.</li>
 *   <li>Updates {@code work_order.assigned_technician_id}.</li>
 *   <li>Publishes {@code TechnicianUnassigned} (outgoing) and {@code TechnicianAssigned}
 *       (incoming) outbox events.</li>
 * </ol>
 *
 * <p>All writes share one {@link org.springframework.transaction.annotation.Transactional}
 * boundary: a rollback undoes all eight writes atomically.
 */
public interface ReassignmentService {

    /**
     * Reassigns {@code workOrderId} from its current technician to {@code incomingTechnicianId}.
     *
     * @param workOrderId                      the work order to reassign
     * @param incomingTechnicianId             the new technician
     * @param reassignedBy                     authenticated dispatcher/admin user ID
     * @param reassignmentReason               controlled reason from {@link ReassignmentReason}
     * @param reasonNotes                      optional free-text supplement
     * @param recommendationSnapshotId         optional snapshot for override tracking
     * @param overrideReason                   free text; required when rank is absent or > 3
     * @param appointmentImpactAcknowledgement required when the work order has a future
     *                                         confirmed appointment window
     * @param expectedVersion                  optimistic-lock version the caller observed
     * @return reassignment result
     * @throws ReassignmentStateException       if state is not eligible for reassignment (409)
     * @throws SameAssigneeException            if incoming = current technician (400)
     * @throws AssignmentService.CertificationGuardException if certification guard fails (422)
     * @throws AppointmentBreachException       if appointment breach is unacknowledged (422)
     * @throws AssignmentService.OverrideReasonRequiredException if override absent when required (400)
     * @throws EligibilityDataException         if eligibility data is unavailable (503)
     */
    ReassignmentResult reassign(
            UUID workOrderId,
            UUID incomingTechnicianId,
            UUID reassignedBy,
            ReassignmentReason reassignmentReason,
            @Nullable String reasonNotes,
            @Nullable UUID recommendationSnapshotId,
            @Nullable String overrideReason,
            @Nullable String appointmentImpactAcknowledgement,
            int expectedVersion);

    /**
     * Returns the ordered assignment history for a work order (oldest first).
     * Includes both active and superseded assignments.
     */
    List<AssignmentHistoryEntry> getHistory(UUID workOrderId);

    // ── Result and exception types ────────────────────────────────────────────

    record ReassignmentResult(
            UUID assignmentId,
            UUID supersededAssignmentId,
            UUID workOrderId,
            UUID technicianId,
            WorkOrderState state,
            Instant reassignedAt,
            String reassignmentReason,
            boolean appointmentImpactRecorded,
            boolean overrideRecorded
    ) {}

    record AssignmentHistoryEntry(
            UUID assignmentId,
            UUID technicianId,
            UUID assignedBy,
            Instant assignedAt,
            @Nullable Instant endAt,
            @Nullable UUID supersededBy,
            @Nullable String reassignmentReason,
            @Nullable String reasonNotes,
            boolean active
    ) {}

    /** Thrown when the work order is not in a state eligible for reassignment. */
    class ReassignmentStateException extends RuntimeException {
        private final WorkOrderState currentState;
        public ReassignmentStateException(WorkOrderState currentState) {
            super("Reassignment is not permitted from state '" + currentState + "'. "
                    + "Eligible states: ASSIGNED, EN_ROUTE, ON_HOLD, IN_PROGRESS.");
            this.currentState = currentState;
        }
        public WorkOrderState getCurrentState() { return currentState; }
    }

    /** Thrown when the incoming technician is the same as the current assignee (no-op). */
    class SameAssigneeException extends RuntimeException {
        public SameAssigneeException() {
            super("The incoming technician is already assigned to this work order. "
                    + "Reassigning to the same technician is a no-op.");
        }
    }

    /** Thrown when the work order has a future confirmed appointment and no acknowledgement was supplied. */
    class AppointmentBreachException extends RuntimeException {
        public AppointmentBreachException(String message) { super(message); }
    }
}
