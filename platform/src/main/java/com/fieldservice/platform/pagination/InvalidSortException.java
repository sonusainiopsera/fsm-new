package com.fieldservice.platform.pagination;

/**
 * Thrown when a client supplies a sort field that is not in the per-resource allow-list,
 * or when the sort token cannot be parsed.
 *
 * <p>Mapped to {@code 400 Bad Request} by the global exception handler with a
 * {@code fieldErrors} entry naming the offending parameter, preventing SQL/JPQL injection
 * via sort parameters.
 */
public class InvalidSortException extends RuntimeException {

    private final String parameterName;

    public InvalidSortException(String parameterName, String message) {
        super(message);
        this.parameterName = parameterName;
    }

    public InvalidSortException(String fieldName) {
        super("Sort field '" + fieldName + "' is not in the allowed sort fields for this resource.");
        this.parameterName = "sort";
    }

    /** The request parameter name that caused the violation (typically {@code "sort"}). */
    public String getParameterName() {
        return parameterName;
    }
}
