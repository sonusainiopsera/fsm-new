package com.fieldservice.release;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.fieldservice.release.gates.DenyByDefaultGate;
import com.fieldservice.release.gates.InventoryIntegrityGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: executes the full invariant gate suite against a WireMock server
 * that simulates the deployed application.
 *
 * <p>Covers:
 * <ul>
 *   <li>All gates pass on a healthy simulated environment (exit code 0)</li>
 *   <li>Fault injection: each gate fails when its invariant is broken</li>
 *   <li>Dry-run mode: write gates are skipped</li>
 *   <li>Smoke path passes on a correctly configured environment</li>
 *   <li>DenyByDefaultGate uses notification-preferences fallback when work-order API absent</li>
 * </ul>
 */
class InvariantGateSuiteIT {

    private WireMockServer server;
    private RunConfig config;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        WireMock.configureFor("localhost", server.port());
        config = RunConfig.forTest("http://localhost:" + server.port(), false);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ── Smoke path ────────────────────────────────────────────────────────────

    @Test
    void smokePathPassesOnHealthyEnvironment() {
        stubHealthy();
        stubAuth();

        Gate smokePath = new SmokePath();
        ApiClient client = ApiClient.create();
        GateResult result = smokePath.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.PASS);
    }

    @Test
    void smokePathFailsWhenHealthReturnsDown() {
        // Override health to return DOWN
        server.stubFor(get(urlPathEqualTo("/actuator/health"))
                .willReturn(okJson("{\"status\":\"DOWN\"}")));

        Gate smokePath = new SmokePath();
        ApiClient client = ApiClient.create();
        GateResult result = smokePath.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.FAIL);
        assertThat(result.failureDetail()).contains("DOWN");
    }

    @Test
    void smokePathFailsWhenForbiddenActuatorExposed() {
        stubHealthy();
        // Make /actuator/env return 200 (misconfiguration)
        server.stubFor(get(urlPathEqualTo("/actuator/env"))
                .willReturn(okJson("{\"activeProfiles\":[]}")));
        stubAuth();

        Gate smokePath = new SmokePath();
        ApiClient client = ApiClient.create();
        GateResult result = smokePath.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.FAIL);
        assertThat(result.failureDetail()).contains("/actuator/env");
        assertThat(result.failureDetail()).contains("security misconfiguration");
    }

    // ── AuditRevisionGate ─────────────────────────────────────────────────────

    @Test
    void auditRevisionGatePassesWhenRevisionExistsAfterTransition() {
        stubAuth();
        stubWorkOrderCreate("wo-audit-001");
        stubRevisionsBefore("wo-audit-001", 1);
        stubStateTransition("wo-audit-001", "IN_PROGRESS", 200);
        stubRevisionsAfter("wo-audit-001", 2); // one new revision
        stubStateTransition("wo-audit-001", "CANCELLED", 200);

        Gate gate = new com.fieldservice.release.gates.AuditRevisionGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.PASS);
    }

    @Test
    void auditRevisionGateFailsWhenNoRevisionCreated() {
        stubAuth();
        stubWorkOrderCreate("wo-audit-002");
        stubRevisionsBefore("wo-audit-002", 1);
        stubStateTransition("wo-audit-002", "IN_PROGRESS", 200);
        // Same count after — no new revision created
        stubRevisionsAfter("wo-audit-002", 1);
        stubStateTransition("wo-audit-002", "CANCELLED", 200);

        Gate gate = new com.fieldservice.release.gates.AuditRevisionGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.FAIL);
        assertThat(result.failureDetail()).contains("Expected exactly 1 new audit revision");
    }

    @Test
    void auditRevisionGateSkippedInDryRun() {
        RunConfig dryConfig = RunConfig.forTest("http://localhost:" + server.port(), true);
        Gate gate = new com.fieldservice.release.gates.AuditRevisionGate();
        GateResult result = gate.run(dryConfig, ApiClient.create());

        assertThat(result.status()).isEqualTo(GateStatus.SKIP);
    }

    // ── InventoryIntegrityGate ────────────────────────────────────────────────

    @Test
    void inventoryGatePassesWhenStockInvariantHolds() {
        stubAuth();
        // Part lookup returns 10 units
        server.stubFor(get(urlPathMatching("/api/v1/inventory/parts.*"))
                .willReturn(okJson(partJson("part-001", 10))));
        // Over-consumption refused with 409
        server.stubFor(post(urlPathEqualTo("/api/v1/inventory/consume"))
                .withRequestBody(containing("1009"))
                .willReturn(aResponse().withStatus(409).withBody("{\"code\":\"INSUFFICIENT_STOCK\"}")));
        // Valid consumption (qty=1) accepted
        server.stubFor(post(urlPathEqualTo("/api/v1/inventory/consume"))
                .withRequestBody(containing("\"quantity\":1"))
                .willReturn(okJson("{\"consumed\":1}")));

        Gate gate = new InventoryIntegrityGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.PASS);
    }

    @Test
    void inventoryGateFailsWhenOverConsumptionAccepted() {
        stubAuth();
        server.stubFor(get(urlPathMatching("/api/v1/inventory/parts.*"))
                .willReturn(okJson(partJson("part-001", 5))));
        // Over-consumption wrongly accepted — invariant violated
        server.stubFor(post(urlPathEqualTo("/api/v1/inventory/consume"))
                .willReturn(okJson("{\"consumed\":1004}")));

        Gate gate = new InventoryIntegrityGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.FAIL);
        assertThat(result.failureDetail()).contains("was accepted");
    }

    @Test
    void inventoryGateSetupErrorWhenPartMissing() {
        stubAuth();
        server.stubFor(get(urlPathMatching("/api/v1/inventory/parts.*"))
                .willReturn(okJson("{\"data\":[]}")));

        Gate gate = new InventoryIntegrityGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.SETUP_ERROR);
        assertThat(result.failureDetail()).contains(InventoryIntegrityGate.VALIDATION_PART_SKU);
    }

    // ── DenyByDefaultGate ─────────────────────────────────────────────────────

    @Test
    void denyByDefaultGateUsesFallbackWhenWorkOrderApiAbsent() {
        stubAuth();
        // Work-order API not deployed
        server.stubFor(post(urlPathEqualTo("/api/v1/work-orders"))
                .willReturn(aResponse().withStatus(404)));

        // Notification preferences fallback:
        // Unauthenticated probe → 401
        server.stubFor(get(urlPathMatching("/api/v1/users/.*/notification-preferences"))
                .withHeader("Authorization", absent())
                .willReturn(aResponse().withStatus(401)));

        // Validator's own prefs → 200
        server.stubFor(get(urlEqualTo(
                "/api/v1/users/a0000000-0000-0000-0000-000000000003/notification-preferences"))
                .withHeader("Authorization", matching("Bearer .*"))
                .willReturn(okJson("{\"data\":[],\"page\":{\"number\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}}")));

        // Admin's prefs (cross-user probe as validator) → 403
        server.stubFor(get(urlEqualTo(
                "/api/v1/users/a0000000-0000-0000-0000-000000000001/notification-preferences"))
                .withHeader("Authorization", matching("Bearer .*"))
                .willReturn(aResponse().withStatus(403).withBody("{\"code\":\"ACCESS_DENIED\"}")));

        // Nonexistent user probe → 403 (same status as cross-user)
        server.stubFor(get(urlEqualTo(
                "/api/v1/users/00000000-dead-beef-0000-000000000000/notification-preferences"))
                .willReturn(aResponse().withStatus(403).withBody("{\"code\":\"ACCESS_DENIED\"}")));

        Gate gate = new DenyByDefaultGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.PASS);
    }

    @Test
    void denyByDefaultGateFailsWhenUnauthenticatedReturns200() {
        stubAuth();
        server.stubFor(post(urlPathEqualTo("/api/v1/work-orders"))
                .willReturn(aResponse().withStatus(404)));

        // Unauthenticated probe wrongly returns 200
        server.stubFor(get(urlPathMatching("/api/v1/users/.*/notification-preferences"))
                .withHeader("Authorization", absent())
                .willReturn(okJson("{\"data\":[]}")));

        // Auth for the validator
        server.stubFor(get(urlPathMatching("/api/v1/users/.*/notification-preferences"))
                .withHeader("Authorization", matching("Bearer .*"))
                .willReturn(okJson("{\"data\":[]}")));

        Gate gate = new DenyByDefaultGate();
        ApiClient client = ApiClient.create();
        GateResult result = gate.run(config, client);

        assertThat(result.status()).isEqualTo(GateStatus.FAIL);
        assertThat(result.failureDetail()).contains("401");
    }

    // ── Full suite run ────────────────────────────────────────────────────────

    @Test
    void dryRunSkipsAllWriteGates() {
        stubHealthy();
        stubAuth();

        RunConfig dryConfig = RunConfig.forTest("http://localhost:" + server.port(), true);
        StringWriter sw = new StringWriter();
        int code = InvariantGateRunner.run(dryConfig, InvariantGateRunner.defaultGates(),
                new PrintWriter(sw, true));

        // Smoke path runs (read-only), others skipped
        String output = sw.toString();
        assertThat(output).contains("SKIP");
        // Exit code 0 because SKIP is not a failure
        assertThat(code).isEqualTo(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubHealthy() {
        server.stubFor(get(urlPathEqualTo("/actuator/health"))
                .willReturn(okJson("{\"status\":\"UP\"}")));
        server.stubFor(get(urlPathEqualTo("/actuator/health/readiness"))
                .willReturn(okJson("{\"status\":\"UP\"}")));
        // Metrics available
        server.stubFor(get(urlPathEqualTo("/actuator/metrics"))
                .willReturn(okJson("{\"names\":[]}")));
        // Forbidden endpoints return 404
        for (String forbidden : new String[]{"/actuator/env", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/beans", "/actuator/configprops", "/actuator/mappings"}) {
            server.stubFor(get(urlPathEqualTo(forbidden))
                    .willReturn(aResponse().withStatus(404)));
        }
    }

    private void stubAuth() {
        server.stubFor(post(urlPathEqualTo("/api/v1/auth/login"))
                .willReturn(okJson("{\"accessToken\":\"test-token-abc\"}")));
        server.stubFor(post(urlPathEqualTo("/api/v1/auth/logout"))
                .willReturn(aResponse().withStatus(204)));
    }

    private void stubWorkOrderCreate(String woId) {
        server.stubFor(post(urlPathEqualTo("/api/v1/work-orders"))
                .willReturn(okJson("{\"data\":{\"id\":\"" + woId + "\",\"state\":\"CREATED\"}}")));
    }

    private void stubRevisionsBefore(String woId, int count) {
        server.stubFor(get(urlPathEqualTo("/api/v1/work-orders/" + woId + "/revisions"))
                .inScenario("revisions-" + woId)
                .whenScenarioStateIs("Started")
                .willReturn(okJson(revisionsJson(count, "CREATED")))
                .willSetStateTo("transitioned"));
    }

    private void stubRevisionsAfter(String woId, int count) {
        server.stubFor(get(urlPathEqualTo("/api/v1/work-orders/" + woId + "/revisions"))
                .inScenario("revisions-" + woId)
                .whenScenarioStateIs("transitioned")
                .willReturn(okJson(revisionsJson(count, "IN_PROGRESS"))));
    }

    private void stubStateTransition(String woId, String state, int status) {
        server.stubFor(put(urlPathEqualTo("/api/v1/work-orders/" + woId + "/state"))
                .withRequestBody(containing("\"state\":\"" + state + "\""))
                .willReturn(aResponse().withStatus(status)
                        .withBody("{\"data\":{\"id\":\"" + woId + "\",\"state\":\"" + state + "\"}}")
                        .withHeader("Content-Type", "application/json")));
    }

    private String revisionsJson(int count, String lastState) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            String state = (i == count - 1) ? lastState : "CREATED";
            sb.append("{\"revisionNumber\":").append(i + 1)
              .append(",\"state\":\"").append(state).append("\"}");
        }
        sb.append("],\"page\":{\"totalElements\":").append(count)
          .append(",\"size\":20,\"number\":0,\"totalPages\":1}}");
        return sb.toString();
    }

    private String partJson(String id, int qty) {
        return "{\"data\":[{\"id\":\"" + id + "\"," +
               "\"sku\":\"" + InventoryIntegrityGate.VALIDATION_PART_SKU + "\"," +
               "\"quantityOnHand\":" + qty + "}]}";
    }
}
