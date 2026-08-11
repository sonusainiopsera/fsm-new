package com.fieldservice.outbox;

import com.fieldservice.outbox.payload.WorkOrderStateChangedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.outbox.PiiRedactionUtility;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable fixture builder for {@code WorkOrderStateChanged} events.
 *
 * <p>Committed here for reuse by WO-005 (poller) and domain epic tests that need
 * pre-built event fixtures without duplicating builder logic.
 */
public final class WorkOrderEventFixtureBuilder {

    public static final String TRACE_ID = "test-trace-00000000-0000-0000-0000-000000000001";

    private WorkOrderEventFixtureBuilder() {}

    /**
     * Builds a {@link DomainEvent} for a work order state change.
     *
     * @param workOrderId UUID of the work order
     * @param fromState   previous state (e.g. "NEW")
     * @param toState     new state (e.g. "ASSIGNED")
     * @param priority    work order priority (e.g. "HIGH")
     * @param actorId     UUID of the authenticated actor performing the transition
     * @return a fully populated {@link DomainEvent} with a UUIDv7 event id
     */
    public static DomainEvent workOrderStateChanged(
            UUID workOrderId, String fromState, String toState,
            String priority, UUID actorId) {
        Map<String, Object> payload = PiiRedactionUtility.toPayloadMap(
                new WorkOrderStateChangedPayload(workOrderId, fromState, toState, priority, Instant.now())
        );
        return DomainEvent.of(
                WorkOrderStateChangedPayload.EVENT_TYPE,
                WorkOrderStateChangedPayload.AGGREGATE_TYPE,
                workOrderId,
                Instant.now(),
                TRACE_ID,
                actorId,
                payload
        );
    }

    /**
     * Builds a {@link DomainEvent} with an explicit event id for uniqueness tests.
     */
    public static DomainEvent workOrderStateChangedWithId(
            UUID eventId, UUID workOrderId, String fromState, String toState,
            String priority, UUID actorId) {
        Map<String, Object> payload = PiiRedactionUtility.toPayloadMap(
                new WorkOrderStateChangedPayload(workOrderId, fromState, toState, priority, Instant.now())
        );
        return new DomainEvent(
                eventId,
                WorkOrderStateChangedPayload.EVENT_TYPE,
                WorkOrderStateChangedPayload.AGGREGATE_TYPE,
                workOrderId,
                Instant.now(),
                TRACE_ID,
                actorId,
                payload
        );
    }
}
