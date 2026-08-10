package com.fieldservice.app;

import com.fieldservice.app.config.WorkerAsyncConfiguration;
import com.fieldservice.testfixtures.PostgresTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code worker} profile against a real PostgreSQL 16 database
 * running in a Testcontainers container.
 *
 * <p>Asserts:</p>
 * <ul>
 *   <li>Context loads with a real database connection.</li>
 *   <li>Scheduler bean is present.</li>
 *   <li>OutboxPoller bean is present.</li>
 *   <li>No business HTTP connector is started (management port only).</li>
 *   <li>Api-profile beans are absent.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("worker")
@Import(PostgresTestContainersConfig.class)
class WorkerProfileIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsWithRealDatabase() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void schedulerBeanIsPresent() {
        TaskScheduler scheduler = applicationContext.getBean("workerTaskScheduler", TaskScheduler.class);
        assertThat(scheduler).isNotNull();
    }

    @Test
    void outboxPollerBeanIsPresent() {
        WorkerAsyncConfiguration.OutboxPoller poller =
                applicationContext.getBean(WorkerAsyncConfiguration.OutboxPoller.class);
        assertThat(poller).isNotNull();
    }

    @Test
    void businessHttpConnectorIsAbsent() {
        // WebEnvironment.NONE ensures no embedded server starts.
        // Additionally, spring.main.web-application-type=none in application-worker.yml
        // prevents any web server from being configured.
        boolean isWebContext = applicationContext instanceof
                org.springframework.boot.web.context.WebServerApplicationContext webCtx
                && webCtx.getWebServer() != null;
        assertThat(isWebContext)
                .as("Worker must not start a business HTTP connector")
                .isFalse();
    }

    @Test
    void apiBeansAreAbsent() {
        assertThat(applicationContext.containsBean("apiWebConfiguration")).isFalse();
        assertThat(applicationContext.containsBean("traceIdFilterRegistration")).isFalse();
    }
}
