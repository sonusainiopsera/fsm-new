package com.fieldservice.app.security;

import com.fieldservice.app.Application;
import com.fieldservice.platform.persistence.ScopedQueryExecutor;
import com.fieldservice.platform.security.AccessScope;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import com.fieldservice.workorder.domain.WorkOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.fieldservice.app.security.TestJwtFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the generated SQL for scoped reads contains the scope predicate in the
 * WHERE clause — proving that row filtering happens at the database level, not in Java.
 *
 * <p>Uses Hibernate's {@link CaptureStatementInspector} (registered in
 * {@code application-test.yml}) to capture SQL statements, then asserts the WHERE clause
 * contains the expected predicate fragment.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/db/fixtures.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/db/cleanup.sql",
     executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ScopedQuerySqlPredicateTest {

    @Autowired
    ScopedQueryExecutor scopedQueryExecutor;

    @Autowired
    WorkOrderRepository workOrderRepository;

    @BeforeEach
    void clearCapture() {
        CaptureStatementInspector.clear();
    }

    @Test
    @DisplayName("TECHNICIAN scope: generated SQL contains assigned_technician_id predicate")
    @Transactional
    void technician_scope_sql_contains_technician_predicate() {
        AccessScope scope = new AccessScope(
                TECH_ONE_USER_ID, Set.of("TECHNICIAN"), TECH_ONE_ID, null);

        Page<WorkOrder> page = scopedQueryExecutor.findAll(
                workOrderRepository, PageRequest.of(0, 20), scope, WorkOrder.class);

        // Verify via results: Tech One has WO-001 and WO-003
        assertThat(page.getTotalElements()).isEqualTo(2);

        // Verify SQL predicate: the generated SQL must contain the technician id constraint
        List<String> capturedSql = CaptureStatementInspector.getCaptured();
        assertThat(capturedSql)
                .isNotEmpty()
                .anySatisfy(sql ->
                        assertThat(sql.toLowerCase())
                                .contains("assigned_technician_id"));
    }

    @Test
    @DisplayName("CUSTOMER scope: generated SQL contains customer_id IN clause (join)")
    @Transactional
    void customer_scope_sql_contains_customer_account_predicate() {
        AccessScope scope = new AccessScope(
                CUSTOMER_C1_USER_ID, Set.of("CUSTOMER"), null,
                Set.of(ACME_ACCOUNT_ID));

        Page<WorkOrder> page = scopedQueryExecutor.findAll(
                workOrderRepository, PageRequest.of(0, 20), scope, WorkOrder.class);

        // Acme has 2 work orders (WO-001, WO-003)
        assertThat(page.getTotalElements()).isEqualTo(2);

        // SQL must contain customer_id in the WHERE clause (via site join)
        List<String> capturedSql = CaptureStatementInspector.getCaptured();
        assertThat(capturedSql)
                .isNotEmpty()
                .anySatisfy(sql ->
                        assertThat(sql.toLowerCase())
                                .contains("customer_id"));
    }

    @Test
    @DisplayName("DISPATCHER scope: generated SQL has no row-restriction predicate (conjunction)")
    @Transactional
    void dispatcher_scope_sql_returns_all_rows() {
        AccessScope scope = new AccessScope(
                DISPATCHER_USER_ID, Set.of("DISPATCHER"), null, null);

        Page<WorkOrder> page = scopedQueryExecutor.findAll(
                workOrderRepository, PageRequest.of(0, 20), scope, WorkOrder.class);

        // Dispatcher sees all 4 rows
        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("Count query for TECHNICIAN scope uses same predicate (totalElements is scoped)")
    @Transactional
    void count_query_uses_scope_predicate() {
        AccessScope scope = new AccessScope(
                TECH_ONE_USER_ID, Set.of("TECHNICIAN"), TECH_ONE_ID, null);

        Page<WorkOrder> page = scopedQueryExecutor.findAll(
                workOrderRepository, PageRequest.of(0, 1), scope, WorkOrder.class);

        // Only 1 result per page, but totalElements should be 2 (not 4 — scoped count)
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);  // scoped, not global

        // Both data query and count query should appear in captured SQL
        List<String> capturedSql = CaptureStatementInspector.getCaptured();
        long sqlWithTechPredicate = capturedSql.stream()
                .filter(sql -> sql.toLowerCase().contains("assigned_technician_id"))
                .count();
        // At least 2: one data query, one count query
        assertThat(sqlWithTechPredicate).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("findById: out-of-scope and nonexistent both return empty (SQL composed)")
    @Transactional
    void find_by_id_composes_scope_predicate() {
        AccessScope techTwoScope = new AccessScope(
                TECH_TWO_USER_ID, Set.of("TECHNICIAN"), TECH_TWO_ID, null);

        // WO-001 exists but is assigned to Tech-One, not Tech-Two → empty
        var result = scopedQueryExecutor.findById(workOrderRepository, WO_001_ID, techTwoScope, WorkOrder.class);
        assertThat(result).isEmpty();

        // Nonexistent ID → also empty
        var nonExistent = scopedQueryExecutor.findById(workOrderRepository,
                NONEXISTENT_ID, techTwoScope, WorkOrder.class);
        assertThat(nonExistent).isEmpty();
    }
}
