package com.fieldservice.support;

import com.fieldservice.security.TestSecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

/**
 * Primary base class for all integration tests in the field-service-api suite.
 *
 * <p>Inherits the singleton PostgreSQL 16 container from {@link PostgresContainerSupport}
 * and adds the full Spring application context, MockMvc, JDBC helpers, and
 * test-specific bean overrides.
 *
 * <h3>Isolation modes</h3>
 * <dl>
 *   <dt>Transactional (default)</dt>
 *   <dd>Annotate the test class or method with {@code @Transactional}. Spring rolls
 *       back the transaction after each test; no explicit cleanup is needed.
 *       Suitable for read-mostly tests and unit-of-work verification without real commits.</dd>
 *   <dt>Truncating (non-transactional)</dt>
 *   <dd>Tests that need real commits — outbox polling, optimistic-lock conflict,
 *       conditional UPDATE — must <strong>not</strong> use {@code @Transactional}.
 *       Call {@link DatabaseCleaner#clean(JdbcTemplate)} in {@code @AfterEach} to
 *       restore the baseline. See {@link IsolationStrategyTest} for a worked example.</dd>
 * </dl>
 *
 * <h3>Audit and outbox helpers</h3>
 * Subclasses can use {@link AuditAssertions} and {@link OutboxAssertions} with the
 * injected {@code jdbc} template to assert Envers revisions and outbox events over
 * plain JDBC, without depending on Hibernate internals or broker state.
 *
 * <h3>Redis</h3>
 * Tests that exercise cache, rate limiting, or the refresh-token denylist should extend
 * {@link RedisContainerSupport} instead of — or in addition to — this class. Only those
 * tests pay the Redis container startup cost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public abstract class AbstractIntegrationTest extends PostgresContainerSupport {

    /**
     * Pre-configured JDBC template bound to the test datasource.
     * Use directly, or pass to {@link DatabaseCleaner}, {@link AuditAssertions},
     * and {@link OutboxAssertions}.
     */
    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * The raw test datasource. Use when you need a {@link java.sql.Connection}
     * with explicit transaction control (e.g., for constraint-violation tests
     * that require a dedicated connection).
     */
    @Autowired
    protected DataSource dataSource;
}
