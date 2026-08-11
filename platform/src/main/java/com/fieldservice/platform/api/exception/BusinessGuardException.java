package com.fieldservice.platform.api.exception;

/**
 * Thrown when a domain-level business guard refuses the operation
 * (e.g. technician lacks a required certification). Maps to HTTP 422.
 *
 * <p>{@code guardSubCode} is an optional guard-specific sub-code (e.g. {@code LABOUR_TIME_MISSING})
 * surfaced inside the {@code WORK_ORDER_GUARD_REFUSED} envelope so the frontend can render
 * targeted remediation. May be {@code null} for non-guard refusals.
 */
public class BusinessGuardException extends RuntimeException {

    private final String guardSubCode;

    public BusinessGuardException(String message) {
        super(message);
        this.guardSubCode = null;
    }

    public BusinessGuardException(String guardSubCode, String message) {
        super(message);
        this.guardSubCode = guardSubCode;
    }

    public String getGuardSubCode() { return guardSubCode; }
}
