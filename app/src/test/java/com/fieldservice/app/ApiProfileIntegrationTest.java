package com.fieldservice.app;

import com.fieldservice.testfixtures.PostgresTestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code api} profile against a real PostgreSQL 16 database
 * running in a Testcontainers container.
 *
 * <p>Asserts:</p>
 * <ul>
 *   <li>Actuator {@code /health} returns UP with a real database connection.</li>
 *   <li>No scheduler beans are registered.</li>
 *   <li>Actuator {@code /env}, {@code /heapdump}, {@code /loggers}, {@code /threaddump}
 *       return 404 (production profile disables them).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "prod"})
@Import(PostgresTestContainersConfig.class)
class ApiProfileIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    void noSchedulerBeansRegisteredInApiProfile() {
        // Zero scheduled tasks in the api profile
        assertThat(applicationContext.containsBean("outboxPoller")).isFalse();
        assertThat(applicationContext.containsBean("workerTaskScheduler")).isFalse();
    }

    @Test
    void sensitiveActuatorEndpointsReturn404InProdProfile() {
        String[] disabledEndpoints = {"/actuator/env", "/actuator/heapdump",
                                       "/actuator/loggers", "/actuator/threaddump"};
        for (String endpoint : disabledEndpoints) {
            ResponseEntity<String> response = restTemplate.getForEntity(endpoint, String.class);
            assertThat(response.getStatusCode())
                    .as("Expected 404 for disabled endpoint %s", endpoint)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void prometheusEndpointIsAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Prometheus format includes metric names
        assertThat(response.getBody()).contains("jvm_");
    }
}
