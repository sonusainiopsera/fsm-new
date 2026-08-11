package com.fieldservice.app.security;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies AC-8: scope changes take effect immediately on the next request.
 * Reassigns a work order from tech-001 to tech-002 and asserts the prior
 * assignee loses read access without any cache invalidation step.
 *
 * <p>Each "simulate request" helper resets the {@link RequestContextHolder} to force
 * a fresh {@link com.fieldservice.platform.security.AccessScopeContext} instance,
 * exactly as would happen across real HTTP requests.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReassignmentRevocationTest extends AbstractIntegrationTest {

    private static final UUID WO_001 = UUID.fromString("bbbbbbbb-0001-0001-0001-000000000001");

    @Autowired WorkOrderRepository repository;
    @Autowired ScopedQueryExecutor executor;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("AC-8: reassigned technician loses access immediately on next request")
    void reassigned_technician_loses_access_immediately() {
        // ── Request 1: tech-001 can see WO-001 ──────────────────────────────
        simulateRequest("user-tech1", "tech-001");
        Page<WorkOrder> before = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(before.getTotalElements()).isEqualTo(1);
        assertThat(before.getContent().get(0).getId()).isEqualTo(WO_001);

        // ── Reassign WO-001 to tech-002 (committed so next query sees new state) ──
        transactionTemplate.executeWithoutResult(tx -> {
            WorkOrder wo = repository.findById(WO_001).orElseThrow();
            wo.assign("tech-002");
            repository.saveAndFlush(wo);
        });

        // ── Request 2: simulate next request as tech-001 — must see nothing ──
        simulateRequest("user-tech1", "tech-001");
        Page<WorkOrder> afterRevocation = executor.findAll(
                repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(afterRevocation.getTotalElements())
                .as("tech-001 must lose access to WO-001 immediately after reassignment "
                        + "— no cache invalidation step required")
                .isZero();

        // ── Request 3: tech-002 now sees WO-001 ──────────────────────────────
        simulateRequest("user-tech2", "tech-002");
        Page<WorkOrder> tech2View = executor.findAll(
                repository, null, PageRequest.of(0, 10), WorkOrder.class);
        // tech-002 was already assigned to WO-002; now also has WO-001
        assertThat(tech2View.getTotalElements()).isEqualTo(2);
    }

    /**
     * Simulates a new HTTP request: resets the request-scope context (so the
     * {@code @RequestScope} {@link com.fieldservice.platform.security.AccessScopeContext}
     * is re-created fresh) and sets the given JWT on the security context.
     */
    private void simulateRequest(String subject, String technicianId) {
        // Reset to a new mock request so @RequestScope beans re-resolve their state
        RequestContextHolder.resetRequestAttributes();
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SecurityContextHolder.clearContext();
        Jwt jwt = Jwt.withTokenValue("test-" + subject)
                .headers(h -> h.put("alg", "HS256"))
                .claim("sub", subject)
                .claim("roles", List.of("TECHNICIAN"))
                .claim("technician_id", technicianId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
