package com.fieldservice.app.pagination;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic 5000-row work-order fixture generator for pagination tests.
 *
 * <p>Rows are inserted with a mix of timestamps — groups of 10 rows share the same
 * {@code created_at} — to exercise the id tie-break in stable ordering.
 * All titles follow the pattern "WO-{i}" and states are "NEW".
 *
 * <p>Insertion is idempotent via {@code ON CONFLICT DO NOTHING}.
 */
public final class WorkOrderFixtureGenerator {

    public static final UUID FIXTURE_CUSTOMER_ID =
            UUID.fromString("00000099-0000-0000-7000-000000000001");
    public static final UUID FIXTURE_SITE_ID =
            UUID.fromString("00000099-0000-0000-7000-000000000002");
    public static final int ROW_COUNT = 5000;

    private WorkOrderFixtureGenerator() {}

    /**
     * Inserts the prerequisite customer, site, and all 5000 work-order rows.
     *
     * @return the list of inserted work-order UUIDs in insertion order
     */
    public static List<UUID> insert(JdbcTemplate jdbc) {
        jdbc.update(
            "INSERT INTO customer (id, name, is_active, created_at, updated_at) " +
            "VALUES (?, 'Pagination Test Customer', TRUE, now(), now()) " +
            "ON CONFLICT DO NOTHING",
            FIXTURE_CUSTOMER_ID);

        jdbc.update(
            "INSERT INTO site (id, name, customer_id, created_at) " +
            "VALUES (?, 'Pagination Test Site', ?, now()) " +
            "ON CONFLICT DO NOTHING",
            FIXTURE_SITE_ID, FIXTURE_CUSTOMER_ID);

        List<UUID> ids = new ArrayList<>(ROW_COUNT);
        List<Object[]> rows = new ArrayList<>(ROW_COUNT);

        // Every 10 rows share the same created_at so id tie-breaking is exercised
        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");

        for (int i = 0; i < ROW_COUNT; i++) {
            // Deterministic UUID using fixed prefix + hex-encoded index
            UUID id = UUID.fromString(String.format(
                "00000099-%04x-%04x-%04x-%012x",
                (i >> 16) & 0xFFFF,
                i & 0xFFFF,
                ((i >> 8) & 0x0FFF) | 0x7000,
                (long) i));

            Instant createdAt = baseTime.plusSeconds(i / 10);

            ids.add(id);
            rows.add(new Object[]{
                id, "WO-" + i, "NEW", FIXTURE_SITE_ID,
                createdAt, createdAt, 0L  // created_at, updated_at, version
            });
        }

        jdbc.batchUpdate(
            "INSERT INTO work_order (id, title, state, site_id, created_at, updated_at, version) " +
            "VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            rows);

        return ids;
    }

    /**
     * Deletes all rows inserted by {@link #insert}.
     */
    public static void delete(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM work_order WHERE site_id = ?", FIXTURE_SITE_ID);
        jdbc.update("DELETE FROM site WHERE id = ?", FIXTURE_SITE_ID);
        jdbc.update("DELETE FROM customer WHERE id = ?", FIXTURE_CUSTOMER_ID);
    }
}
