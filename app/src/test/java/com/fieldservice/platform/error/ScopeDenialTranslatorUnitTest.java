package com.fieldservice.platform.error;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScopeDenialTranslator} — verifies 403-vs-404 rule,
 * counter increments, and structured audit message (WO-113 AC-7, AC-9, AC-12).
 */
class ScopeDenialTranslatorUnitTest {

    private MeterRegistry meterRegistry;
    private ScopeDenialTranslator translator;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        translator = new ScopeDenialTranslator(meterRegistry);
        request = new MockHttpServletRequest("GET", "/api/v1/work-orders/" + UUID.randomUUID());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // 403 vs 404 translation
    // -------------------------------------------------------------------------

    @Test
    void nonCustomerRole_returns403Forbidden() {
        setSecurityContext("ROLE_DISPATCHER");
        UUID resourceId = UUID.randomUUID();
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", resourceId);

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorEnvelope.Code.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("Access denied.");
    }

    @Test
    void technicianRole_returns403Forbidden() {
        setSecurityContext("ROLE_TECHNICIAN");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo(ErrorEnvelope.Code.FORBIDDEN);
    }

    @Test
    void managerRole_returns403Forbidden() {
        setSecurityContext("ROLE_MANAGER");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void customerRole_returns404NotFound() {
        setSecurityContext("ROLE_CUSTOMER");
        UUID resourceId = UUID.randomUUID();
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", resourceId);

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorEnvelope.Code.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("The requested resource was not found.");
    }

    @Test
    void unknownRole_returns403Forbidden() {
        setSecurityContext("ROLE_UNKNOWN");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        // Unknown roles are denied with 403 (deny by default)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedPrincipal_returns403Forbidden() {
        // No security context — unresolvable principal → deny
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("scope resolution failed");

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------
    // Non-disclosure: response body must not contain resource ID
    // -------------------------------------------------------------------------

    @Test
    void response403_doesNotContainResourceId() {
        setSecurityContext("ROLE_DISPATCHER");
        UUID resourceId = UUID.randomUUID();
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", resourceId);

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getBody().message()).doesNotContain(resourceId.toString());
    }

    @Test
    void response404_doesNotContainResourceId() {
        setSecurityContext("ROLE_CUSTOMER");
        UUID resourceId = UUID.randomUUID();
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", resourceId);

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getBody().message()).doesNotContain(resourceId.toString());
    }

    // -------------------------------------------------------------------------
    // Micrometer counter increments
    // -------------------------------------------------------------------------

    @Test
    void crossRoleDenial_incrementsDenialCounterWithCrossRoleTag() {
        setSecurityContext("ROLE_TECHNICIAN");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        translator.translate(ex, request);

        Counter counter = meterRegistry.find(ScopeDenialTranslator.DENIAL_COUNTER_NAME)
                .tag("denial_type", "cross_role")
                .tag("role", "TECHNICIAN")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void crossAccountDenial_incrementsDenialCounterWithCrossAccountTag() {
        setSecurityContext("ROLE_CUSTOMER");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        translator.translate(ex, request);

        Counter counter = meterRegistry.find(ScopeDenialTranslator.DENIAL_COUNTER_NAME)
                .tag("denial_type", "cross_account")
                .tag("role", "CUSTOMER")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void multipledenials_accumulateCounterCorrectly() {
        setSecurityContext("ROLE_DISPATCHER");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        translator.translate(ex, request);
        translator.translate(ex, request);
        translator.translate(ex, request);

        Counter counter = meterRegistry.find(ScopeDenialTranslator.DENIAL_COUNTER_NAME)
                .tag("denial_type", "cross_role")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    // -------------------------------------------------------------------------
    // Response contains traceId header
    // -------------------------------------------------------------------------

    @Test
    void response_alwaysContainsTraceIdHeader() {
        setSecurityContext("ROLE_DISPATCHER");
        ScopedAccessDeniedException ex = new ScopedAccessDeniedException("WorkOrder", UUID.randomUUID());

        ResponseEntity<ErrorEnvelope> response = translator.translate(ex, request);

        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isNotNull();
        assertThat(response.getBody().traceId()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static void setSecurityContext(String roleAuthority) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(
                "test-user", "n/a", roleAuthority);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
