package com.fieldservice.platform.api;

/**
 * Thrown when a business rule guard prevents the operation from proceeding,
 * even though the caller is authenticated and the resource exists.
 * Maps to HTTP 422 with code {@link ErrorCode#GUARD_REFUSED}.
 */
public class BusinessGuardException extends ApiException {

    public BusinessGuardException(String rule, String reason) {
        super(ErrorCode.GUARD_REFUSED, "Business rule '" + rule + "' refused: " + reason);
    }

    public BusinessGuardException(String message) {
        super(ErrorCode.GUARD_REFUSED, message);
    }
}
