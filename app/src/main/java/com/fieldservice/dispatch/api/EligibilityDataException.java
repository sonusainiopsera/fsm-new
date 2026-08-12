package com.fieldservice.dispatch.api;

/**
 * Thrown when certification or availability data cannot be loaded for eligibility evaluation.
 *
 * <p>The gate never fails open: callers must treat this exception as a hard failure and
 * must not fall back to an unfiltered candidate list. Mapped to HTTP 503 in
 * {@code GlobalExceptionHandler}.
 */
public class EligibilityDataException extends RuntimeException {

    public EligibilityDataException(String message, Throwable cause) {
        super(message, cause);
    }

    public EligibilityDataException(String message) {
        super(message);
    }
}
