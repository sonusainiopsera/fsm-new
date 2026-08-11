package com.fieldservice.architecture.fixture;

import org.springframework.stereotype.Service;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This class exists solely to prove that the ArchUnit method-security rule in
 * {@link com.fieldservice.architecture.MethodSecurityTest} fires when a public service
 * method lacks an authorization annotation (@PreAuthorize / @PostAuthorize / @Secured).
 *
 * <p>This class must never be used in production code or wired as a Spring bean.
 */
@Service
public class UnannotatedServiceMethod {

    /** No @PreAuthorize — deliberately missing annotation to trigger the ArchUnit rule. */
    public String publicMethodWithoutAnnotation(String input) {
        return input;
    }

    /** Private methods are excluded from the rule — this is fine. */
    private String privateHelperMethod() {
        return "ok";
    }
}
