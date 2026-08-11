package com.fieldservice.platform.pagination;

/**
 * Thrown when a client supplies a sort field that is not in the resource's
 * {@link SortAllowList}. Mapped to 400 by {@code GlobalExceptionHandler}.
 */
public class InvalidSortException extends RuntimeException {

    private final String field;

    public InvalidSortException(String field) {
        super("Sort field not in allow-list: " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
