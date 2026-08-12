package com.fieldservice.support;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Truncates all mutable tables in FK-safe order, resetting identity sequences, while
 * preserving reference data seeded by Flyway migrations.
 *
 * <p>Use this in {@code @AfterEach} for tests that make real commits and cannot rely on
 * transactional rollback (e.g. outbox polling, optimistic-lock conflict, conditional UPDATE).
 *
 * <p>Tables excluded from truncation (reference / vocabulary data):
 * <ul>
 *   <li>{@code sla_policy} — seeded by V2; read-only in tests</li>
 *   <li>{@code hold_reason} — seeded by V18; read-only in tests</li>
 * </ul>
 *
 * <p>The ordered list is derived from the FK graph: children before parents, so
 * {@code TRUNCATE … CASCADE} never violates a constraint.
 */
@Component
public class DatabaseCleaner {

    static final List<String> MUTABLE_TABLES = List.of(
            "ai_interaction_rating",
            "ai_interaction",
            "stock_ledger",
            "work_order_part",
            "stock_balance",
            "assignment",
            "outbox_event",
            "idempotency_key",
            "login_audit",
            "work_order",
            "technician_certification",
            "asset",
            "stock_location",
            "site",
            "technician",
            "refresh_token",
            "refresh_token_family",
            "user_role",
            "role_assignment",
            "app_user",
            "customer",
            "part"
    );

    private final DataSource dataSource;

    public DatabaseCleaner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Truncates all mutable tables via a single {@code TRUNCATE … RESTART IDENTITY CASCADE}
     * statement. Reference tables ({@code sla_policy}, {@code hold_reason}) are left intact.
     */
    public void truncateAll() {
        String tables = String.join(", ", MUTABLE_TABLES);
        String sql = "TRUNCATE TABLE " + tables + " RESTART IDENTITY CASCADE";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DatabaseCleaner.truncateAll failed", e);
        }
    }
}
