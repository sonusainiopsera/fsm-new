package com.fieldservice.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two isolation strategies are effective.
 *
 * <h3>Transactional isolation</h3>
 * Two ordered tests write and then assert absence. Spring rolls back after each test;
 * data inserted in test 1 is invisible in test 2.
 *
 * <h3>Truncating isolation</h3>
 * Same proof for non-transactional tests. Data is inserted in test 1 with a real commit;
 * {@link DatabaseCleaner#clean} in {@code @AfterEach} removes it; test 2 asserts absence.
 *
 * <p>These tests do NOT use the fixture data (V100–V109). They insert into
 * a leaf table ({@code hold_reason}) that has no FK dependencies into production
 * data, so there is no risk of cross-contamination.
 */
@DisplayName("Integration test isolation strategies")
class IsolationStrategyTest extends AbstractIntegrationTest {

    private static final String INSERT_SQL =
            "INSERT INTO hold_reason (code, label, requires_note) VALUES (?, ?, false) " +
            "ON CONFLICT (code) DO NOTHING";

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM hold_reason WHERE code LIKE 'ISOLATION-TEST-%'";

    // =========================================================================
    // Transactional isolation
    // =========================================================================

    /**
     * Proves that {@code @Transactional} rolls back inserted rows after each test.
     * The two tests are ordered so that absence is verified in the second test.
     */
    @Nested
    @DisplayName("Transactional rollback isolation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Transactional
    class TransactionalIsolationTest {

        @Autowired
        JdbcTemplate jdbc;

        @Test
        @Order(1)
        @DisplayName("Test 1: inserts a row inside the transaction")
        void insertRow() {
            jdbc.update(INSERT_SQL, "ISOLATION-TEST-TX-001", "Transactional Isolation Test Row");

            int count = countIsolationRows();
            assertThat(count)
                    .as("Row must be visible within the same transaction")
                    .isGreaterThanOrEqualTo(1);
            // Transaction will roll back after this method — row disappears
        }

        @Test
        @Order(2)
        @DisplayName("Test 2: row from test 1 is absent after rollback")
        void rowAbsentAfterRollback() {
            int count = countIsolationRows();
            assertThat(count)
                    .as("Row inserted in test 1 must be absent after its transaction rolled back")
                    .isZero();
        }

        private int countIsolationRows() {
            Integer count = jdbc.queryForObject(COUNT_SQL, Integer.class);
            return count != null ? count : 0;
        }
    }

    // =========================================================================
    // Truncating isolation
    // =========================================================================

    /**
     * Proves that {@link DatabaseCleaner#clean} removes committed rows between tests.
     * These tests are NOT {@code @Transactional} — rows are really committed.
     */
    @Nested
    @DisplayName("DatabaseCleaner truncating isolation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TruncatingIsolationTest {

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        DataSource dataSource;

        @AfterEach
        void cleanUp() {
            DatabaseCleaner.clean(jdbc);
        }

        @Test
        @Order(1)
        @DisplayName("Test 1: inserts a row with a real commit")
        void insertRowWithRealCommit() throws Exception {
            // Commit directly so the row survives outside the Spring tx boundary
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO hold_reason (code, label, requires_note) " +
                               "VALUES ('ISOLATION-TEST-TRUNC-001', 'Truncating Test', false) " +
                               "ON CONFLICT (code) DO NOTHING");
                }
            }
            // Verify row is present before cleanup
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hold_reason WHERE code = 'ISOLATION-TEST-TRUNC-001'",
                    Integer.class);
            assertThat(count).as("Row must be present after real commit").isGreaterThan(0);
            // DatabaseCleaner.clean() is called in @AfterEach — row disappears
        }

        @Test
        @Order(2)
        @DisplayName("Test 2: row from test 1 is absent after DatabaseCleaner.clean()")
        void rowAbsentAfterClean() {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hold_reason WHERE code = 'ISOLATION-TEST-TRUNC-001'",
                    Integer.class);
            assertThat(count)
                    .as("Row inserted in test 1 must be absent after DatabaseCleaner.clean()")
                    .isZero();
        }

        @Test
        @DisplayName("Reference tables preserved after clean()")
        void referenceDataPreserved() {
            DatabaseCleaner.clean(jdbc);

            // role and sla_policy are excluded from truncation
            Integer roleCount = jdbc.queryForObject("SELECT COUNT(*) FROM role", Integer.class);
            Integer slaCount  = jdbc.queryForObject("SELECT COUNT(*) FROM sla_policy", Integer.class);

            assertThat(roleCount).as("role table must be preserved by DatabaseCleaner").isGreaterThan(0);
            assertThat(slaCount).as("sla_policy table must be preserved by DatabaseCleaner").isGreaterThan(0);
        }
    }

    // =========================================================================
    // DatabaseCleaner unit tests (no container needed)
    // =========================================================================

    @Nested
    @DisplayName("DatabaseCleaner unit tests")
    class DatabaseCleanerUnitTest {

        @Test
        @DisplayName("Reference tables are not in the truncation list")
        void referenceTablesNotInTruncationList() {
            for (String ref : DatabaseCleaner.REFERENCE_TABLES) {
                assertThat(DatabaseCleaner.truncationOrder())
                        .as("Reference table '%s' must NOT appear in the truncation order list", ref)
                        .doesNotContain(ref);
            }
        }

        @Test
        @DisplayName("Truncation order list is non-empty")
        void truncationOrderIsNonEmpty() {
            assertThat(DatabaseCleaner.truncationOrder()).isNotEmpty();
        }

        @Test
        @DisplayName("flyway_schema_history is not in truncation list")
        void flywayTableExcluded() {
            assertThat(DatabaseCleaner.truncationOrder())
                    .doesNotContain("flyway_schema_history");
        }
    }
}
