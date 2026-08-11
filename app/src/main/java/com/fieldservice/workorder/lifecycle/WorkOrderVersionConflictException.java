package com.fieldservice.workorder.lifecycle;

import com.fieldservice.workorder.WorkOrderErrorCodes;

/** Thrown when expectedVersion does not match the persisted version (stale read or concurrent write). */
public class WorkOrderVersionConflictException extends RuntimeException {

    public WorkOrderVersionConflictException(String message) {
        super(message);
    }

    public WorkOrderVersionConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public String getErrorCode() {
        return WorkOrderErrorCodes.VERSION_CONFLICT;
    }
}
