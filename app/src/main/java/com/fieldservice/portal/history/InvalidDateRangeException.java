package com.fieldservice.portal.history;

/**
 * Thrown when the portal history date range filter is invalid:
 * the range is inverted ({@code fromDate} after {@code toDate}),
 * or the span exceeds the configured maximum.
 *
 * <p>Mapped to HTTP 400 by {@link com.fieldservice.portal.web.PortalExceptionAdvice}.
 */
public class InvalidDateRangeException extends RuntimeException {

    private final String field;
    private final String message;

    public InvalidDateRangeException(String field, String message) {
        super(message);
        this.field   = field;
        this.message = message;
    }

    public String getField()          { return field; }

    @Override
    public String getMessage()        { return message; }
}
