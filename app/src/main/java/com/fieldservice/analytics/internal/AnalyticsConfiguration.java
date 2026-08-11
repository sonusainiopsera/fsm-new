package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.internal.quality.RepeatVisitLinker;
import com.fieldservice.platform.outbox.EventHandler;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring configuration for the analytics module.
 *
 * <p>Registers:
 * <ul>
 *   <li>One {@link EventHandler} bean per supported outbox event type, each
 *       delegating to {@link KpiOutboxConsumer} — the outbox poller auto-discovers
 *       these via {@code List<EventHandler>} injection.</li>
 *   <li>An optional replica {@link DataSource} when {@code app.analytics.replica-url}
 *       is present in the environment; falls back to the primary DataSource otherwise.</li>
 *   <li>An {@code analyticsJdbcTemplate} qualified bean used by all
 *       {@link KpiAggregator} implementations for read-replica aggregation queries.</li>
 * </ul>
 */
@Configuration
class AnalyticsConfiguration {

    // ---------------------------------------------------------------
    // EventHandler registrations (one per supported event type)
    // ---------------------------------------------------------------

    @Bean
    EventHandler workOrderCreatedAnalyticsHandler(KpiOutboxConsumer consumer) {
        return KpiOutboxConsumer.handlerFor("WORK_ORDER_CREATED", consumer);
    }

    @Bean
    EventHandler workOrderTransitionAnalyticsHandler(KpiOutboxConsumer consumer,
                                                      RepeatVisitLinker linker) {
        return new EventHandler() {
            @Override
            public String supportedEventType() { return "WORK_ORDER_TRANSITION"; }

            @Override
            public void handle(com.fieldservice.platform.api.DomainEvent event) throws Exception {
                linker.processEvent(event);
                consumer.accept(event);
            }
        };
    }

    @Bean
    EventHandler assignmentCreatedAnalyticsHandler(KpiOutboxConsumer consumer) {
        return KpiOutboxConsumer.handlerFor("ASSIGNMENT_CREATED", consumer);
    }

    @Bean
    EventHandler slaBreachAnalyticsHandler(KpiOutboxConsumer consumer) {
        return KpiOutboxConsumer.handlerFor("SLA_BREACH", consumer);
    }

    @Bean
    EventHandler slaAtRiskAnalyticsHandler(KpiOutboxConsumer consumer) {
        return KpiOutboxConsumer.handlerFor("SLA_AT_RISK", consumer);
    }

    @Bean
    EventHandler labourEntryAddedAnalyticsHandler(KpiOutboxConsumer consumer) {
        return KpiOutboxConsumer.handlerFor("LABOUR_ENTRY_ADDED", consumer);
    }

    // ---------------------------------------------------------------
    // Replica DataSource (optional — falls back to primary)
    // ---------------------------------------------------------------

    @Bean
    @Qualifier("replicaDataSource")
    @ConditionalOnProperty(prefix = "app.analytics", name = "replica-url")
    DataSource replicaDataSource(
            @Value("${app.analytics.replica-url}")          String replicaUrl,
            @Value("${spring.datasource.username}")          String username,
            @Value("${spring.datasource.password}")          String password,
            @Value("${app.analytics.replica-pool-size:5}")   int    poolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(replicaUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(poolSize);
        ds.setReadOnly(true);
        ds.setPoolName("analytics-replica");
        return ds;
    }

    // ---------------------------------------------------------------
    // Analytics JdbcTemplate (replica-routed where configured)
    // ---------------------------------------------------------------

    @Bean
    @Qualifier("analyticsJdbcTemplate")
    JdbcTemplate analyticsJdbcTemplate(
            DataSource primaryDataSource,
            @Autowired(required = false) @Qualifier("replicaDataSource") DataSource replicaDataSource) {
        DataSource target = (replicaDataSource != null) ? replicaDataSource : primaryDataSource;
        return new JdbcTemplate(target);
    }
}
