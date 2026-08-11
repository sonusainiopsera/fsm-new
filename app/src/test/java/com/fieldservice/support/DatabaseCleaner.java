package com.fieldservice.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Ordered TRUNCATE helper for integration tests that need real commits
 * (outbox polling, optimistic-lock conflict, conditional UPDATE).
 *
 * <p>Transactional tests should use Spring's {@code @Transactional} + {@code @Rollback(true)}
 * (the default) and never call this cleaner. Non-transactional tests — those that need
 * the commit to hit the database — call {@link #clean(JdbcTemplate)} in {@code @AfterEach}
 * or {@code @AfterAll} to reset to the post-Flyway fixture baseline.
 *
 * <h3>What is cleaned</h3>
 * All non-reference, non-infrastructure tables are truncated with
 * {@code RESTART IDENTITY CASCADE}. PostgreSQL evaluates FK constraints in
 * dependency order automatically when CASCADE is specified, so listing order
 * does not need to be manually topologically sorted.
 *
 * <h3>What is preserved</h3>
 * <ul>
 *   <li>{@code role} — V4 seed data; immutable reference.</li>
 *   <li>{@code sla_policy} — V2 + seed-core.sql; preserved so tests that use
 *       SLA deadline derivation do not need to re-seed.</li>
 *   <li>{@code flyway_schema_history} — Flyway internal table; never touched.</li>
 * </ul>
 *
 * <h3>Isolation proof</h3>
 * See {@link IsolationStrategyTest} for an ordered two-test proof that data
 * written in one test is absent in the next.
 */
public final class DatabaseCleaner {

    /**
     * All tables eligible for truncation, in an order that minimises FK-violation
     * errors on databases without CASCADE support. With CASCADE, order is advisory
     * only but kept here for documentation clarity.
     */
    private static final List<String> TRUNCATION_ORDER = List.of(
            // Envers audit tables (depend on revinfo)
            "work_order_part_aud",
            "work_order_hold_aud",
            "role_assignment_aud",
            "sla_policy_aud",
            "technician_certification_aud",
            "assignment_aud",
            "site_aud",
            "app_user_aud",
            "work_order_aud",
            // Revision info (referenced by audit tables)
            "revinfo",
            // Application transactional tables (leaf → root)
            "outbox_event",
            "idempotency_key",
            "work_order_part",
            "work_order_part_consumption",
            "work_order_competency",
            "stock_ledger",
            "stock_balance",
            "stock_location",
            "refresh_token",
            "refresh_token_family",
            "role_assignment",
            "assignment",
            "work_order_hold",
            "work_order",
            "asset",
            "site",
            "technician_certification",
            "technician",
            "customer",
            "app_user",
            // Parts and labour — non-reference test data
            "labour_time_record",
            "part",
            "hold_reason",
            "stream_ticket"
    );

    /**
     * Tables that are intentionally excluded from truncation because they hold
     * reference/seed data that tests rely on being present.
     */
    public static final List<String> REFERENCE_TABLES = List.of(
            "role",
            "sla_policy",
            "flyway_schema_history"
    );

    private DatabaseCleaner() {}

    /**
     * Truncates all non-reference tables and restarts identity sequences.
     *
     * <p>Must be called outside any Spring-managed transaction to ensure the
     * truncation actually commits (otherwise it would roll back with the test tx).
     *
     * @param jdbc a {@link JdbcTemplate} connected to the test database
     */
    public static void clean(JdbcTemplate jdbc) {
        // Build a single TRUNCATE statement for all eligible tables that exist in the schema.
        // Tables may not exist if a migration added them after this list was written;
        // the existence check prevents spurious failures.
        String existenceCheck =
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = ANY(?)";

        List<String> existing = jdbc.queryForList(
                existenceCheck,
                String.class,
                new Object[]{TRUNCATION_ORDER.toArray(new String[0])}
        );

        if (existing.isEmpty()) {
            return;
        }

        // Build: TRUNCATE table1, table2, ... RESTART IDENTITY CASCADE
        String tables = String.join(", ", existing);
        jdbc.execute("TRUNCATE " + tables + " RESTART IDENTITY CASCADE");
    }

    /**
     * Returns the ordered truncation list for tests that need to selectively
     * clean a subset of tables.
     */
    public static List<String> truncationOrder() {
        return TRUNCATION_ORDER;
    }
}
