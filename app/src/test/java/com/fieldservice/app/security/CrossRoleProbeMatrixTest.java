package com.fieldservice.app.security;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.ScopedAccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Cross-role probe matrix: drives every scoped WorkOrder read as:
 * DISPATCHER, MANAGER, TECHNICIAN (assigned), TECHNICIAN (unassigned),
 * CUSTOMER (owning), CUSTOMER (non-owning), unauthenticated.
 *
 * Fixtures (loaded by V2__test_fixtures.sql):
 *   - wo-001: site-001 (account ca-001), assigned to tech-001
 *   - wo-002: site-002 (account ca-002), assigned to tech-002
 *
 * Expected outcomes per AC-4, AC-5, AC-6, AC-10.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CrossRoleProbeMatrixTest extends AbstractIntegrationTest {

    // UUIDs matching V2__test_fixtures.sql
    private static final UUID WO_001 = UUID.fromString("bbbbbbbb-0001-0001-0001-000000000001");
    private static final UUID WO_002 = UUID.fromString("bbbbbbbb-0002-0002-0002-000000000002");
    private static final UUID CA_001 = UUID.fromString("cccccccc-0001-0001-0001-000000000001");
    private static final UUID CA_002 = UUID.fromString("cccccccc-0002-0002-0002-000000000002");

    @Autowired WorkOrderRepository repository;
    @Autowired ScopedQueryExecutor executor;

    // ── DISPATCHER sees all ───────────────────────────────────────────────────

    @Test
    @DisplayName("DISPATCHER sees both work orders")
    void dispatcher_sees_all_work_orders() {
        authenticate("disp-1", List.of("DISPATCHER"), null, null);
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // ── MANAGER sees all ──────────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER sees both work orders")
    void manager_sees_all_work_orders() {
        authenticate("mgr-1", List.of("MANAGER"), null, null);
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // ── TECHNICIAN sees only their work orders ────────────────────────────────

    @Test
    @DisplayName("TECHNICIAN1 sees only WO-001 (their assigned work order)")
    void technician1_sees_only_own_work_order() {
        authenticate("user-tech1", List.of("TECHNICIAN"), "tech-001", null);
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(WO_001);
    }

    @Test
    @DisplayName("TECHNICIAN2 sees only WO-002 (their assigned work order)")
    void technician2_sees_only_own_work_order() {
        authenticate("user-tech2", List.of("TECHNICIAN"), "tech-002", null);
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(WO_002);
    }

    @Test
    @DisplayName("TECHNICIAN (unassigned, tech-999) sees no work orders")
    void technician_unassigned_sees_nothing() {
        authenticate("user-tech3", List.of("TECHNICIAN"), "tech-999", null);
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("TECHNICIAN sees scoped totalElements, not global count")
    void technician_total_elements_reflects_scoped_count() {
        authenticate("user-tech1", List.of("TECHNICIAN"), "tech-001", null);
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        // Scoped count must be 1, not 2 (global)
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    // ── CUSTOMER sees only their account's work orders ────────────────────────

    @Test
    @DisplayName("CUSTOMER1 (ca-001) sees only WO-001")
    void customer1_sees_only_own_work_order() {
        authenticate("cust-1", List.of("CUSTOMER"), null, List.of(CA_001.toString()));
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(WO_001);
    }

    @Test
    @DisplayName("CUSTOMER2 (ca-002) sees only WO-002")
    void customer2_sees_only_own_work_order() {
        authenticate("cust-2", List.of("CUSTOMER"), null, List.of(CA_002.toString()));
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(WO_002);
    }

    @Test
    @DisplayName("CUSTOMER1 fetching WO-002 gets ScopedAccessDeniedException (non-disclosure)")
    void customer1_probing_other_work_order_gets_denied() {
        authenticate("cust-1", List.of("CUSTOMER"), null, List.of(CA_001.toString()));
        assertThatThrownBy(() -> executor.requireById(repository, WO_002, WorkOrder.class))
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    @Test
    @DisplayName("CUSTOMER1 fetching nonexistent WO gets same exception as out-of-scope (non-disclosure)")
    void customer1_fetching_nonexistent_wo_gets_same_exception() {
        authenticate("cust-1", List.of("CUSTOMER"), null, List.of(CA_001.toString()));
        UUID nonexistent = UUID.randomUUID();
        assertThatThrownBy(() -> executor.requireById(repository, nonexistent, WorkOrder.class))
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    @Test
    @DisplayName("Multi-account CUSTOMER sees work orders from both accounts")
    void multi_account_customer_sees_union() {
        authenticate("multi-cust", List.of("CUSTOMER"), null,
                List.of(CA_001.toString(), CA_002.toString()));
        Page<WorkOrder> page = executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    // ── Unauthenticated ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Unauthenticated caller gets ScopedAccessDeniedException")
    void unauthenticated_gets_denied() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> executor.findAll(repository, null, PageRequest.of(0, 10), WorkOrder.class))
                .isInstanceOf(ScopedAccessDeniedException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void authenticate(String subject, List<String> roles, String technicianId,
                               List<String> customerAccountIds) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", subject);
        claims.put("roles", roles);
        if (technicianId != null) claims.put("technician_id", technicianId);
        if (customerAccountIds != null) claims.put("customer_account_ids", customerAccountIds);

        Jwt jwt = Jwt.withTokenValue("test-token")
                .headers(h -> h.put("alg", "HS256"))
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
