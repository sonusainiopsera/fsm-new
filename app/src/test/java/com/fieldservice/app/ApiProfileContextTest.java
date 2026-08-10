package com.fieldservice.app;

import com.fieldservice.app.config.ApiWebConfiguration;
import com.fieldservice.app.config.WorkerAsyncConfiguration;
import com.fieldservice.app.filter.TraceIdFilter;
import com.fieldservice.app.guard.ProfileValidator;
import com.fieldservice.testfixtures.PostgresTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load test for the {@code api} profile.
 *
 * <p>Asserts:</p>
 * <ul>
 *   <li>Spring context loads successfully with the api profile and Testcontainers PostgreSQL.</li>
 *   <li>Api-profile beans ({@link ApiWebConfiguration}, {@link TraceIdFilter}) are present.</li>
 *   <li>Worker-profile beans ({@link WorkerAsyncConfiguration.OutboxPoller}) are absent.</li>
 *   <li>Actuator {@code /health} and {@code /prometheus} return 200.</li>
 *   <li>Actuator {@code /env} and {@code /heapdump} return 404 (not exposed).</li>
 *   <li>TraceId is included in the {@code X-Trace-Id} response header.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "prod"})
@Import(PostgresTestContainersConfig.class)
class ApiProfileContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

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
    void apiWebConfigurationBeanIsPresent() {
        assertThat(applicationContext.containsBean("apiWebConfiguration")).isTrue();
    }

    @Test
    void traceIdFilterBeanIsPresent() {
        assertThat(applicationContext.containsBean("traceIdFilter")).isTrue();
    }

    @Test
    void profileValidatorBeanIsPresent() {
        assertThat(applicationContext.containsBean("profileValidator")).isTrue();
        ProfileValidator validator = applicationContext.getBean(ProfileValidator.class);
        assertThat(validator).isNotNull();
    }

    @Test
    void workerOutboxPollerBeanIsAbsent() {
        // OutboxPoller must NOT be present on the api-only deployable
        assertThat(applicationContext.containsBean("outboxPoller")).isFalse();
    }

    @Test
    void workerAsyncConfigurationBeanIsAbsent() {
        assertThat(applicationContext.containsBean("workerAsyncConfiguration")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Actuator endpoint exposure
    // -------------------------------------------------------------------------

    @Test
    void actuatorHealthReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorPrometheusReturns200() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorEnvReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/env", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void actuatorHeapdumpReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/heapdump", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // TraceId propagation
    // -------------------------------------------------------------------------

    @Test
    void actuatorHealthResponseContainsTraceIdHeader() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();
    }

    @Test
    void incomingTraceIdIsPropagatedInResponse() {
        String incomingTraceId = "test-trace-abc-123";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(TraceIdFilter.TRACE_ID_HEADER, incomingTraceId);
        org.springframework.http.HttpEntity<Void> request = new org.springframework.http.HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health",
                org.springframework.http.HttpMethod.GET,
                request,
                String.class
        );

        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(incomingTraceId);
    }
}
