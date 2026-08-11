package com.fieldservice.workorder.holds;

/**
 * API response DTO for a single active hold reason. Also used as the Redis cache unit.
 */
public record HoldReasonResponse(String code, String label, int sortOrder) {

    public static HoldReasonResponse from(HoldReason hr) {
        return new HoldReasonResponse(hr.getCode(), hr.getLabel(), hr.getSortOrder());
    }
}
