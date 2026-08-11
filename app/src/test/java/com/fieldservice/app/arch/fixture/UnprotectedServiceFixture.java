package com.fieldservice.app.arch.fixture;

import org.springframework.stereotype.Service;

/**
 * Deliberately non-compliant fixture used by {@code MethodSecurityTest} to prove
 * that the ArchUnit rule fires when a {@code @Service} method lacks {@code @PreAuthorize}.
 *
 * <p>This class must NOT be added to the production component scan — it lives in the
 * test-only {@code arch.fixture} sub-package and is never wired into the application
 * context. It is intentionally non-compliant and is expected to fail the method-security
 * ArchUnit check.
 */
@Service
public class UnprotectedServiceFixture {

    /**
     * Public service method intentionally missing {@code @PreAuthorize}.
     * The ArchUnit rule in {@code MethodSecurityTest} must detect this and raise an error.
     */
    public Object sensitiveOperation() {
        throw new UnsupportedOperationException("fixture — never executed");
    }
}
