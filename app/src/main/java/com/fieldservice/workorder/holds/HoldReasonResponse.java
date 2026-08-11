package com.fieldservice.workorder.holds;

/**
 * DTO returned by {@code GET /api/v1/work-orders/hold-reasons}.
 */
public record HoldReasonResponse(
        String code,
        String label,
        int sortOrder,
        boolean pausesSLAClock
) {}
