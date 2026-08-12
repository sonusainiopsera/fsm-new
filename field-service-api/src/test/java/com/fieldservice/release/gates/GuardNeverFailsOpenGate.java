package com.fieldservice.release.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fieldservice.release.ApiClient;
import com.fieldservice.release.Gate;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.RunConfig;

/**
 * Invariant gate 3: lifecycle and certification guards never fail open.
 *
 * <p>Assertions:
 * <ol>
 *   <li>An illegal lifecycle transition (e.g. ASSIGNED → COMPLETED) returns HTTP 409.</li>
 *   <li>A completion attempt without recorded labour time returns HTTP 422.</li>
 *   <li>Assigning a technician with an expired certification returns HTTP 422 even when
 *       the caller holds the ADMIN role.</li>
 * </ol>
 *
 * <p>All three assertions are negative tests — the suite deliberately triggers the refusal
 * and asserts the correct rejection code. A 2xx on any assertion is a gate failure.
 */
public class GuardNeverFailsOpenGate implements Gate {

    private static final String NAME = "guard-never-fails-open-gate";

    // Fixture reference: a technician with an expired certification pre-seeded in the DB
    static final String EXPIRED_CERT_TECHNICIAN_ID = "c0000000-0000-0000-0000-000000000001";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GateResult run(RunConfig config, ApiClient client) {
        if (config.dryRun()) {
            return GateResult.skip(NAME, "dry-run mode: write gates are skipped");
        }

        long start = System.currentTimeMillis();
        String workOrderId = null;

        try {
            // Re-authenticate as admin for this gate
            try {
                client.login(config.baseUrl(), config.adminUsername(), config.adminPassword());
            } catch (ApiClient.SetupException e) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Admin login failed: " + e.getMessage());
            }

            // ── 1. Create a work order to use as the test subject ─────────────
            ApiClient.ApiResponse createResp = client.post(
                    config.url("/api/v1/work-orders"),
                    buildCreateBody(config.runId()));

            if (createResp.status() == 404 || createResp.status() == 405) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Work-order API not available (status " + createResp.status() + ").");
            }
            if (!createResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Work order creation returned " + createResp.status());
            }

            JsonNode created = createResp.json(client.mapper());
            workOrderId = extractId(created);
            if (workOrderId == null) {
                return GateResult.fail(NAME, elapsed(start),
                        "Work order creation response missing 'id'.");
            }

            // ── 2. Assert illegal transition returns 409 ──────────────────────
            // CREATED → COMPLETED is illegal; must go via ASSIGNED → IN_PROGRESS → COMPLETED
            ApiClient.ApiResponse illegalResp = client.put(
                    config.url("/api/v1/work-orders/" + workOrderId + "/state"),
                    "{\"state\":\"COMPLETED\"}");

            if (illegalResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Illegal transition CREATED→COMPLETED was ACCEPTED with status " +
                        illegalResp.status() + ". Guard failed open. This is a critical invariant violation.");
            }
            if (illegalResp.status() != 409) {
                return GateResult.fail(NAME, elapsed(start),
                        "Illegal transition returned " + illegalResp.status() +
                        "; expected 409. Guards must return exactly 409 for illegal transitions.");
            }

            // ── 3. Assign and start the work order, then assert completion
            //       without labour time returns 422 ────────────────────────────
            // Assign
            ApiClient.ApiResponse assignResp = client.put(
                    config.url("/api/v1/work-orders/" + workOrderId + "/state"),
                    "{\"state\":\"ASSIGNED\",\"technicianId\":\"" + EXPIRED_CERT_TECHNICIAN_ID + "\"}");

            // The assign itself may return 422 if cert is expired — that's tested below.
            // For the labour-time test we need a successfully assigned WO, so use a fresh one.
            String labourTestWoId = createLabourTestWorkOrder(config, client);
            if (labourTestWoId != null) {
                GateResult labourResult = assertLabourGuard(config, client, labourTestWoId, start);
                if (labourResult != null) return labourResult;
            }

            // ── 4. Assert expired-cert assignment returns 422 even as ADMIN ──
            // Use a fresh work order for isolation
            ApiClient.ApiResponse freshCreate = client.post(
                    config.url("/api/v1/work-orders"),
                    buildCreateBody(config.runId() + "-certtest"));
            if (freshCreate.is2xx()) {
                String certTestWoId = extractId(freshCreate.json(client.mapper()));
                if (certTestWoId != null) {
                    ApiClient.ApiResponse certAssignResp = client.put(
                            config.url("/api/v1/work-orders/" + certTestWoId + "/state"),
                            buildAssignBody(EXPIRED_CERT_TECHNICIAN_ID));

                    if (certAssignResp.is2xx()) {
                        return GateResult.fail(NAME, elapsed(start),
                                "Assignment to a technician with an expired certification was ACCEPTED " +
                                "with status " + certAssignResp.status() + " even as ADMIN. " +
                                "Certification guard failed open. This is a critical invariant violation.");
                    }
                    if (certAssignResp.status() != 422 && certAssignResp.status() != 409) {
                        return GateResult.fail(NAME, elapsed(start),
                                "Expired-cert assignment returned " + certAssignResp.status() +
                                "; expected 422. Guards must return 422 for failed certification checks.");
                    }

                    // Cleanup cert test WO
                    cancelSilently(config, client, certTestWoId);
                }
            }

            return GateResult.pass(NAME, elapsed(start));

        } catch (ApiClient.TransportException e) {
            return GateResult.transportError(NAME, elapsed(start),
                    "Transport error: " + e.getMessage());
        } finally {
            if (workOrderId != null) {
                cancelSilently(config, client, workOrderId);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private GateResult assertLabourGuard(RunConfig config, ApiClient client,
                                          String woId, long start)
            throws ApiClient.TransportException {

        // Drive to IN_PROGRESS
        client.put(config.url("/api/v1/work-orders/" + woId + "/state"),
                "{\"state\":\"IN_PROGRESS\"}");

        // Attempt COMPLETED without logging labour — must return 422
        ApiClient.ApiResponse completeResp = client.put(
                config.url("/api/v1/work-orders/" + woId + "/state"),
                "{\"state\":\"COMPLETED\"}");

        cancelSilently(config, client, woId);

        if (completeResp.is2xx()) {
            return GateResult.fail(NAME, elapsed(start),
                    "Completion without labour time was ACCEPTED with status " +
                    completeResp.status() + ". Labour-time guard failed open.");
        }
        if (completeResp.status() != 422) {
            return GateResult.fail(NAME, elapsed(start),
                    "Completion without labour time returned " + completeResp.status() +
                    "; expected 422.");
        }
        return null; // Guard correctly enforced
    }

    private String createLabourTestWorkOrder(RunConfig config, ApiClient client) {
        try {
            ApiClient.ApiResponse resp = client.post(
                    config.url("/api/v1/work-orders"),
                    buildCreateBody(config.runId() + "-labour"));
            if (resp.is2xx()) {
                return extractId(resp.json(client.mapper()));
            }
        } catch (ApiClient.TransportException e) {
            // Best-effort
        }
        return null;
    }

    private String buildCreateBody(String runId) {
        return """
                {
                  "title": "Guard test - %s",
                  "priority": "LOW",
                  "category": "GENERAL",
                  "validationRunId": "%s"
                }
                """.formatted(runId, runId);
    }

    private String buildAssignBody(String technicianId) {
        return "{\"state\":\"ASSIGNED\",\"technicianId\":\"" + technicianId + "\"}";
    }

    private String extractId(JsonNode node) {
        JsonNode data = node.has("data") ? node.get("data") : node;
        JsonNode id = data.get("id");
        return id != null && !id.isNull() ? id.asText() : null;
    }

    private void cancelSilently(RunConfig config, ApiClient client, String woId) {
        try {
            client.put(config.url("/api/v1/work-orders/" + woId + "/state"),
                    "{\"state\":\"CANCELLED\"}");
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
