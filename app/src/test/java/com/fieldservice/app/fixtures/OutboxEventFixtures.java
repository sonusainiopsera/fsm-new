package com.fieldservice.app.fixtures;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.Confidential;
import com.fieldservice.platform.outbox.Restricted;
import com.fieldservice.platform.util.UuidV7;

import java.time.Instant;
import java.util.UUID;

/**
 * Reusable fixtures for outbox tests (WO-004) and downstream domain stories (WO-005+).
 *
 * <p>Provides:
 * <ul>
 *   <li>Purpose-built payload records for common event types.</li>
 *   <li>Fixture builders returning pre-populated {@link DomainEvent} instances.</li>
 *   <li>Payload records with annotated PII fields for redaction tests.</li>
 * </ul>
 */
public final class OutboxEventFixtures {

    public static final String WORK_ORDER_ASSIGNED = "WORK_ORDER_ASSIGNED";
    public static final String WORK_ORDER_COMPLETED = "WORK_ORDER_COMPLETED";
    public static final String AGGREGATE_WORK_ORDER = "WORK_ORDER";

    private OutboxEventFixtures() {}

    // ---- Payload records -------------------------------------------------------

    /**
     * Minimal payload for a work-order-assigned event.
     * No PII fields — safe to publish directly.
     */
    public static class WorkOrderAssignedPayload {
        public String reference;
        public String priority;
        public UUID   technicianId;

        public WorkOrderAssignedPayload(String reference, String priority, UUID technicianId) {
            this.reference    = reference;
            this.priority     = priority;
            this.technicianId = technicianId;
        }
    }

    /**
     * Payload containing a {@code @Restricted} field (password hash) to exercise
     * the fail-fast rejection in {@link com.fieldservice.platform.outbox.PiiRedaction}.
     */
    public static class PayloadWithRestrictedField {
        public String userId;
        @Restricted
        public String passwordHash;

        public PayloadWithRestrictedField(String userId, String passwordHash) {
            this.userId       = userId;
            this.passwordHash = passwordHash;
        }
    }

    /**
     * Payload containing {@code @Confidential} fields (contact details and GPS)
     * to exercise masking in {@link com.fieldservice.platform.outbox.PiiRedaction}.
     */
    public static class PayloadWithConfidentialFields {
        public String workOrderRef;
        @Confidential
        public String contactEmail;
        @Confidential
        public String contactPhone;
        @Confidential
        public Double gpsLatitude;
        @Confidential
        public Double gpsLongitude;

        public PayloadWithConfidentialFields(String workOrderRef, String contactEmail,
                                            String contactPhone,
                                            Double gpsLatitude, Double gpsLongitude) {
            this.workOrderRef  = workOrderRef;
            this.contactEmail  = contactEmail;
            this.contactPhone  = contactPhone;
            this.gpsLatitude   = gpsLatitude;
            this.gpsLongitude  = gpsLongitude;
        }
    }

    // ---- Event builders --------------------------------------------------------

    public static DomainEvent workOrderAssigned(UUID workOrderId, String traceId) {
        return new DomainEvent(
                UuidV7.generate(),
                WORK_ORDER_ASSIGNED,
                AGGREGATE_WORK_ORDER,
                workOrderId,
                Instant.now(),
                traceId,
                UUID.randomUUID(),
                new WorkOrderAssignedPayload("WO-TEST-001", "HIGH", UUID.randomUUID())
        );
    }

    public static DomainEvent workOrderCompleted(UUID workOrderId, String traceId) {
        return new DomainEvent(
                UuidV7.generate(),
                WORK_ORDER_COMPLETED,
                AGGREGATE_WORK_ORDER,
                workOrderId,
                Instant.now(),
                traceId,
                UUID.randomUUID(),
                new WorkOrderAssignedPayload("WO-TEST-001", "HIGH", null)
        );
    }
}
