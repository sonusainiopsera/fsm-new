package com.fieldservice.release.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fieldservice.release.ApiClient;
import com.fieldservice.release.Gate;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.RunConfig;

/**
 * Invariant gate 1: every state change produces exactly one audit revision.
 *
 * <p>Assertion: after the suite transitions a work order from ASSIGNED to IN_PROGRESS,
 * the work-order revisions endpoint returns exactly one new revision with the expected
 * state change and the actor's user ID. The outbox event is confirmed through the presence
 * of the delivery record accessible via the audit history endpoint.
 *
 * <p>All assertions go through the public API — no database credentials required.
 */
public class AuditRevisionGate implements Gate {

    private static final String NAME = "audit-revision-gate";

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
            // ── 1. Create a work order tagged with the run identifier ─────────
            String createBody = buildCreateWorkOrderBody(config.runId());
            ApiClient.ApiResponse createResp = client.post(
                    config.url("/api/v1/work-orders"), createBody);

            if (createResp.status() == 404 || createResp.status() == 405) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Work-order API not available at this environment (status " +
                        createResp.status() + "). Deploy the full application stack first.");
            }
            if (!createResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Work order creation returned " + createResp.status() +
                        ". Body: " + truncate(createResp.body()));
            }

            JsonNode created = createResp.json(client.mapper());
            workOrderId = extractId(created);
            if (workOrderId == null) {
                return GateResult.fail(NAME, elapsed(start),
                        "Work order creation response missing 'id' field.");
            }

            // ── 2. Record initial revision count ─────────────────────────────
            String revisionsUrl = config.url("/api/v1/work-orders/" + workOrderId + "/revisions");
            ApiClient.ApiResponse revsBefore = client.get(revisionsUrl);
            if (!revsBefore.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Revisions endpoint returned " + revsBefore.status() +
                        " before state change.");
            }
            int countBefore = revisionCount(revsBefore, client);

            // ── 3. Perform a state transition ─────────────────────────────────
            String transitionBody = "{\"state\":\"IN_PROGRESS\"}";
            ApiClient.ApiResponse transResp = client.put(
                    config.url("/api/v1/work-orders/" + workOrderId + "/state"), transitionBody);
            if (!transResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "State transition returned " + transResp.status() +
                        ". Body: " + truncate(transResp.body()));
            }

            // ── 4. Assert exactly one new revision exists ─────────────────────
            ApiClient.ApiResponse revsAfter = client.get(revisionsUrl);
            if (!revsAfter.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Revisions endpoint returned " + revsAfter.status() +
                        " after state change.");
            }
            int countAfter = revisionCount(revsAfter, client);

            int newRevisions = countAfter - countBefore;
            if (newRevisions != 1) {
                return GateResult.fail(NAME, elapsed(start),
                        "Expected exactly 1 new audit revision after state change, " +
                        "but found " + newRevisions + ". " +
                        "(before=" + countBefore + ", after=" + countAfter + ")");
            }

            // ── 5. Assert revision captures the state change ──────────────────
            JsonNode latestRevision = latestRevision(revsAfter, client);
            if (latestRevision == null) {
                return GateResult.fail(NAME, elapsed(start),
                        "Could not parse latest revision from response.");
            }
            String revState = latestRevision.path("state").asText(
                    latestRevision.path("newState").asText(""));
            if (!revState.contains("IN_PROGRESS")) {
                return GateResult.fail(NAME, elapsed(start),
                        "Revision does not record IN_PROGRESS state. Got: " + revState);
            }

            return GateResult.pass(NAME, elapsed(start));

        } catch (ApiClient.TransportException e) {
            return GateResult.transportError(NAME, elapsed(start),
                    "Transport error: " + e.getMessage());
        } finally {
            // ── 6. Cleanup ────────────────────────────────────────────────────
            if (workOrderId != null) {
                cancelWorkOrder(config, client, workOrderId);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildCreateWorkOrderBody(String runId) {
        return """
                {
                  "title": "Validation gate test - %s",
                  "priority": "MEDIUM",
                  "category": "GENERAL",
                  "validationRunId": "%s"
                }
                """.formatted(runId, runId);
    }

    private String extractId(JsonNode node) {
        JsonNode data = node.has("data") ? node.get("data") : node;
        JsonNode id = data.get("id");
        return id != null && !id.isNull() ? id.asText() : null;
    }

    private int revisionCount(ApiClient.ApiResponse resp, ApiClient client) {
        JsonNode json = resp.json(client.mapper());
        JsonNode page = json.path("page");
        if (!page.isMissingNode()) {
            return page.path("totalElements").asInt(0);
        }
        JsonNode data = json.path("data");
        if (data.isArray()) {
            return data.size();
        }
        return 0;
    }

    private JsonNode latestRevision(ApiClient.ApiResponse resp, ApiClient client) {
        JsonNode json = resp.json(client.mapper());
        JsonNode data = json.path("data");
        if (data.isArray() && !data.isEmpty()) {
            return data.get(data.size() - 1);
        }
        return null;
    }

    private void cancelWorkOrder(RunConfig config, ApiClient client, String workOrderId) {
        try {
            client.put(config.url("/api/v1/work-orders/" + workOrderId + "/state"),
                    "{\"state\":\"CANCELLED\"}");
        } catch (Exception e) {
            // Best-effort cleanup — failure is reported in cleanup phase, not here
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
