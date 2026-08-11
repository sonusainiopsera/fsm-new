package com.fieldservice.platform.api.exception;

/**
 * Thrown when a client supplies a sort field that is not in the resource's
 * {@link com.fieldservice.platform.pagination.SortAllowList}.
 *
 * <p>Maps to HTTP 400 with a {@code fieldErrors} entry naming the {@code sort} parameter.
 */
public class InvalidSortException extends RuntimeException {

    private final String field;
    private final String rejectedValue;

    public InvalidSortException(String field, String rejectedValue) {
        super("Sort field '" + rejectedValue + "' is not permitted for this resource.");
        this.field = field;
        this.rejectedValue = rejectedValue;
    }

    public String getField()         { return field; }
    public String getRejectedValue() { return rejectedValue; }
}
