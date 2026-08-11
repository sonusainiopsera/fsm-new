package com.fieldservice.support;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Base class for all integration tests.
 *
 * <p>Boots the full Spring application context against the singleton PostgreSQL 16
 * container provided by {@link PostgresContainerSupport}. Flyway runs the production
 * migration set (including V4 reference data) on first startup. Subsequent tests share
 * the same migrated schema.
 *
 * <h2>Isolation strategies</h2>
 * <ul>
 *   <li><strong>Transactional rollback</strong> (default for read-mostly tests): annotate
 *       the test class or method with {@code @Transactional}. Spring rolls back after each
 *       test method — no cleanup required, but real commits (outbox, optimistic lock) are
 *       not visible within the same transaction.</li>
 *   <li><strong>Truncating</strong> (for tests that need real commits): do NOT annotate
 *       with {@code @Transactional}. Use {@link DatabaseCleaner#truncateAll()} in an
 *       {@code @AfterEach} to restore a clean slate. Reference data seeded by Flyway V4
 *       is never truncated.</li>
 * </ul>
 *
 * <h2>Fixtures</h2>
 * Reference data (customers, sites, parts, stock locations) is seeded by
 * {@code V4__seed_reference_data.sql} and lives in the {@code ffffffff-…} UUID space.
 * Per-test data should use random UUIDs or object-mother helpers to avoid collisions.
 *
 * <h2>Redis tests</h2>
 * Tests that exercise cache, rate limiting, or refresh-token denylist should extend
 * {@link RedisContainerSupport} instead.
 */
@Tag("integration")
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest extends PostgresContainerSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected PlatformTransactionManager txManager;

    protected TransactionTemplate tx;

    @BeforeEach
    void initTx() {
        tx = new TransactionTemplate(txManager);
    }
}
