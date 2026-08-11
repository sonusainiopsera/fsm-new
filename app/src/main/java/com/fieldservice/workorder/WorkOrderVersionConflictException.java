package com.fieldservice.workorder;

import java.util.UUID;

/**
 * Thrown when the client-supplied {@code expectedVersion} does not match the persisted
 * version, or when a concurrent commit wins the JPA optimistic lock race.
 *
 * <p>Maps to HTTP 409 with code {@link WorkOrderErrorCodes#WORK_ORDER_VERSION_CONFLICT}.
 */
public class WorkOrderVersionConflictException extends RuntimeException {

    private final UUID workOrderId;
    private final int expectedVersion;

    public WorkOrderVersionConflictException(UUID workOrderId, int expectedVersion) {
        super("Version conflict on work order " + workOrderId
                + ": expectedVersion=" + expectedVersion);
        this.workOrderId = workOrderId;
        this.expectedVersion = expectedVersion;
    }

    public UUID getWorkOrderId() {
        return workOrderId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }
}
