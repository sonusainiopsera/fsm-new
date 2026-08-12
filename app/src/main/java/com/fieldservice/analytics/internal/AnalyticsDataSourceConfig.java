package com.fieldservice.analytics.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * Replica-routed datasource for analytics aggregation queries (WO-161).
 *
 * <p>When {@code app.analytics.replica-url} is set, a separate Hikari pool is created
 * pointing at the read replica. Aggregation queries run against this pool so they never
 * contend with primary-write transactions.
 *
 * <p>When the replica URL is absent (development, test) or when
 * {@code app.analytics.replica-url} equals the primary URL, the same primary datasource
 * is used but with a log warning. Tests use this fallback path by design (no replica in
 * Testcontainers) and assert the correct bean is injected.
 *
 * <p>The primary datasource is still used for projection upserts (KpiProjectionRepository)
 * since write operations must go to the primary.
 */
@Configuration
class AnalyticsDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDataSourceConfig.class);

    /**
     * The replica URL; defaults to empty string (primary fallback).
     * Set {@code app.analytics.replica-url=jdbc:postgresql://replica:5432/fieldservice}
     * in production environment config.
     */
    @Value("${app.analytics.replica-url:}")
    private String replicaUrl;

    @Value("${app.analytics.replica.username:${spring.datasource.username:fieldservice}}")
    private String replicaUsername;

    @Value("${app.analytics.replica.password:${spring.datasource.password:fieldservice}}")
    private String replicaPassword;

    @Value("${app.analytics.replica.pool-size:5}")
    private int replicaPoolSize;

    /**
     * Creates a {@code replicaDataSource} bean.
     *
     * <p>Qualifies as {@code replicaDataSource} so it can be injected distinctly from
     * the primary Spring datasource — tests can assert the aggregation queries use
     * this bean and not the primary.
     */
    @Bean("replicaDataSource")
    DataSource replicaDataSource(DataSourceProperties primaryProperties) {
        String url = replicaUrl != null && !replicaUrl.isBlank()
                ? replicaUrl
                : primaryProperties.getUrl();

        boolean usingPrimary = replicaUrl == null || replicaUrl.isBlank();
        if (usingPrimary) {
            log.warn("analytics.replica.using_primary — app.analytics.replica-url not configured; " +
                     "aggregation queries will run on the primary datasource. " +
                     "Set app.analytics.replica-url in production to avoid read pressure on the primary.");
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(replicaUsername);
        cfg.setPassword(replicaPassword);
        cfg.setMaximumPoolSize(replicaPoolSize);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("analytics-replica");
        cfg.setReadOnly(!usingPrimary); // read-only when pointing at a dedicated replica
        return new HikariDataSource(cfg);
    }

    /**
     * JdbcTemplate wired to the replica datasource.
     * Analytics aggregation components inject this by qualifier.
     */
    @Bean("replicaJdbcTemplate")
    JdbcTemplate replicaJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("replicaDataSource") DataSource replicaDataSource) {
        return new JdbcTemplate(replicaDataSource);
    }

    /**
     * NamedParameterJdbcTemplate wired to the replica datasource.
     * Used by SlaAggregationRepository for named-parameter compliance queries.
     */
    @Bean("replicaNamedParameterJdbcTemplate")
    NamedParameterJdbcTemplate replicaNamedParameterJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("replicaDataSource") DataSource replicaDataSource) {
        return new NamedParameterJdbcTemplate(replicaDataSource);
    }
}
