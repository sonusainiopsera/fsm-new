package com.fieldservice.app;

import com.fieldservice.app.config.ApiWebConfiguration;
import com.fieldservice.app.config.WorkerAsyncConfiguration;
import com.fieldservice.app.filter.TraceIdFilter;
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
 * Context-load test for the {@code worker} profile.
 *
 * <p>Asserts:</p>
 * <ul>
 *   <li>Spring context loads successfully with the worker profile and Testcontainers PostgreSQL.</li>
 *   <li>Worker-profile beans ({@link WorkerAsyncConfiguration}, {@link WorkerAsyncConfiguration.OutboxPoller},
 *       {@link TaskScheduler}) are present.</li>
 *   <li>Api-profile beans ({@link ApiWebConfiguration}, {@link TraceIdFilter}) are absent.</li>
 *   <li>No embedded HTTP connector starts for business endpoints (web-application-type=none).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("worker")
@Import(PostgresTestContainersConfig.class)
class WorkerProfileContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    // -------------------------------------------------------------------------
    // Context loading
    // -------------------------------------------------------------------------

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Profile-conditional bean presence
    // -------------------------------------------------------------------------

    @Test
    void workerAsyncConfigurationBeanIsPresent() {
        assertThat(applicationContext.containsBean("workerAsyncConfiguration")).isTrue();
    }

    @Test
    void outboxPollerBeanIsPresent() {
        assertThat(applicationContext.containsBean("outboxPoller")).isTrue();
        WorkerAsyncConfiguration.OutboxPoller poller =
                applicationContext.getBean(WorkerAsyncConfiguration.OutboxPoller.class);
        assertThat(poller).isNotNull();
    }

    @Test
    void workerTaskSchedulerBeanIsPresent() {
        assertThat(applicationContext.containsBean("workerTaskScheduler")).isTrue();
        TaskScheduler scheduler = applicationContext.getBean("workerTaskScheduler", TaskScheduler.class);
        assertThat(scheduler).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Profile-conditional bean ABSENCE
    // -------------------------------------------------------------------------

    @Test
    void apiWebConfigurationBeanIsAbsent() {
        // Api web configuration must NOT be present on the worker-only deployable
        assertThat(applicationContext.containsBean("apiWebConfiguration")).isFalse();
    }

    @Test
    void traceIdFilterBeanIsAbsent() {
        // TraceIdFilter and its registration are only wired in ApiWebConfiguration (api profile)
        assertThat(applicationContext.containsBean("traceIdFilter")).isFalse();
        assertThat(applicationContext.containsBean("traceIdFilterRegistration")).isFalse();
    }

    // -------------------------------------------------------------------------
    // No embedded web server for business endpoints
    // -------------------------------------------------------------------------

    @Test
    void noEmbeddedWebServerStartsForBusinessEndpoints() {
        // The worker profile sets spring.main.web-application-type=none so no
        // Tomcat/Jetty/Undertow server starts for business traffic.
        // WebServerApplicationContext would be present if a web server started.
        boolean isWebContext = applicationContext instanceof
                org.springframework.boot.web.context.WebServerApplicationContext webCtx
                && webCtx.getWebServer() != null;
        assertThat(isWebContext)
                .as("Worker profile must not start a business HTTP server")
                .isFalse();
    }
}
