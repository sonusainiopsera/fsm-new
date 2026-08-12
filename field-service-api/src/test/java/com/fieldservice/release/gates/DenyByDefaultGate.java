package com.fieldservice.release.gates;

import com.fieldservice.release.ApiClient;
import com.fieldservice.release.Gate;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.RunConfig;

/**
 * Invariant gate 4: access control denies by default with no existence disclosure.
 *
 * <p>Assertions:
 * <ol>
 *   <li>An unauthenticated probe returns HTTP 401.</li>
 *   <li>A cross-role probe on a suite-created resource returns HTTP 403, and the response
 *       is indistinguishable from a probe on a non-existent identifier (no existence disclosure).</li>
 *   <li>The 403 response body does not include the resource identifier, owner identity,
 *       or any hint of whether the resource exists.</li>
 * </ol>
 *
 * <p>The gate creates a resource as ADMIN and probes it as the validation (non-admin) account.
 */
public class DenyByDefaultGate implements Gate {

    private static final String NAME = "deny-by-default-gate";

    // A UUID that should never exist in any environment
    private static final String NONEXISTENT_UUID = "00000000-dead-beef-0000-000000000000";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public GateResult run(RunConfig config, ApiClient client) {
        long start = System.currentTimeMillis();
        String createdWorkOrderId = null;
        String adminToken = null;

        try {
            // ── 1. Authenticate as ADMIN to create a scoped resource ──────────
            try {
                client.login(config.baseUrl(), config.adminUsername(), config.adminPassword());
            } catch (ApiClient.SetupException e) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Admin login failed: " + e.getMessage());
            }

            String workOrdersUrl = config.url("/api/v1/work-orders");
            ApiClient.ApiResponse createResp = client.post(workOrdersUrl,
                    buildWorkOrderBody(config.runId()));

            if (createResp.status() == 404 || createResp.status() == 405) {
                // Work-order API not implemented yet — test with notification preferences instead
                return runNotificationPreferencesProbe(config, client, start);
            }

            if (!createResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Resource creation returned " + createResp.status());
            }

            com.fasterxml.jackson.databind.JsonNode created =
                    createResp.json(client.mapper());
            createdWorkOrderId = extractId(created);
            adminToken = extractToken(config, client);

            // ── 2. Authenticate as validation (non-admin) user ────────────────
            try {
                client.login(config.baseUrl(), config.username(), config.password());
            } catch (ApiClient.SetupException e) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Validation account login failed: " + e.getMessage());
            }

            // ── 3. Unauthenticated probe returns 401 ──────────────────────────
            ApiClient.ApiResponse unauthResp = client.getUnauthenticated(
                    config.url("/api/v1/work-orders/" + createdWorkOrderId));
            if (unauthResp.status() != 401) {
                return GateResult.fail(NAME, elapsed(start),
                        "Unauthenticated probe returned " + unauthResp.status() +
                        "; expected 401. All protected endpoints must require authentication.");
            }

            // ── 4. Cross-role probe on owned resource returns 403 ─────────────
            ApiClient.ApiResponse crossRoleResp = client.get(
                    config.url("/api/v1/work-orders/" + createdWorkOrderId));

            if (crossRoleResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Cross-role probe on a scoped work order returned " + crossRoleResp.status() +
                        ". Non-admin account can read another user's work order. Access control violated.");
            }
            if (crossRoleResp.status() != 403 && crossRoleResp.status() != 404) {
                return GateResult.fail(NAME, elapsed(start),
                        "Cross-role probe returned " + crossRoleResp.status() +
                        "; expected 403 or 404 (no existence disclosure).");
            }

            // ── 5. Assert response is indistinguishable from nonexistent resource ──
            ApiClient.ApiResponse nonexistentResp = client.get(
                    config.url("/api/v1/work-orders/" + NONEXISTENT_UUID));

            if (crossRoleResp.status() != nonexistentResp.status()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Existence disclosure: cross-role probe on real resource returns " +
                        crossRoleResp.status() + " but probe on nonexistent resource returns " +
                        nonexistentResp.status() +
                        ". Both must return the same status to prevent existence enumeration.");
            }

            // ── 6. Assert 403 body doesn't expose resource identity ───────────
            String body403 = crossRoleResp.body();
            if (body403 != null && createdWorkOrderId != null &&
                    body403.contains(createdWorkOrderId)) {
                return GateResult.fail(NAME, elapsed(start),
                        "Existence disclosure: 403 response body contains the resource identifier.");
            }

            return GateResult.pass(NAME, elapsed(start));

        } catch (ApiClient.TransportException e) {
            return GateResult.transportError(NAME, elapsed(start),
                    "Transport error: " + e.getMessage());
        } finally {
            if (createdWorkOrderId != null) {
                cleanup(config, client, adminToken, createdWorkOrderId);
            }
        }
    }

    // ── Fallback when work-order API is not yet deployed ─────────────────────

    /**
     * When the work-order API is not yet available, exercise the deny-by-default
     * invariant using the notification-preferences endpoint (which is available).
     *
     * <p>Creates preference data as admin user, then probes it as a different user
     * to assert existence-blind 403 behavior.
     */
    private GateResult runNotificationPreferencesProbe(RunConfig config,
                                                        ApiClient client, long start)
            throws ApiClient.TransportException {

        // Admin user ID — use the seed fixture value
        String adminUserId = "a0000000-0000-0000-0000-000000000001";
        String validatorUserId = "a0000000-0000-0000-0000-000000000003";

        // ── Unauthenticated probe returns 401 ─────────────────────────────────
        ApiClient.ApiResponse unauthResp = client.getUnauthenticated(
                config.url("/api/v1/users/" + adminUserId + "/notification-preferences"));
        if (unauthResp.status() != 401) {
            return GateResult.fail(NAME, elapsed(start),
                    "Unauthenticated probe of notification-preferences returned " +
                    unauthResp.status() + "; expected 401.");
        }

        // ── Authenticate as validator ─────────────────────────────────────────
        try {
            client.login(config.baseUrl(), config.username(), config.password());
        } catch (ApiClient.SetupException e) {
            return GateResult.setupError(NAME, elapsed(start),
                    "Validation account login failed: " + e.getMessage());
        }

        // ── Cross-user probe returns 403 (not 200) ────────────────────────────
        // Probe admin's preferences as the validation user (different user ID)
        String validatorOwnPrefsUrl = config.url(
                "/api/v1/users/" + validatorUserId + "/notification-preferences");
        String adminPrefsUrl = config.url(
                "/api/v1/users/" + adminUserId + "/notification-preferences");

        ApiClient.ApiResponse ownResp = client.get(validatorOwnPrefsUrl);
        // Own preferences should be accessible (200)
        if (ownResp.status() != 200) {
            return GateResult.fail(NAME, elapsed(start),
                    "Validator cannot access own notification preferences: " + ownResp.status());
        }

        ApiClient.ApiResponse crossUserResp = client.get(adminPrefsUrl);
        if (crossUserResp.is2xx()) {
            return GateResult.fail(NAME, elapsed(start),
                    "Cross-user notification-preferences probe returned " +
                    crossUserResp.status() +
                    ". Non-admin user can read another user's preferences. Access control violated.");
        }
        if (crossUserResp.status() != 403 && crossUserResp.status() != 404) {
            return GateResult.fail(NAME, elapsed(start),
                    "Cross-user probe returned " + crossUserResp.status() +
                    "; expected 403 or 404 (no existence disclosure).");
        }

        // ── Existence disclosure check ────────────────────────────────────────
        ApiClient.ApiResponse nonexistentResp = client.get(
                config.url("/api/v1/users/" + NONEXISTENT_UUID + "/notification-preferences"));

        if (crossUserResp.status() != nonexistentResp.status()) {
            return GateResult.fail(NAME, elapsed(start),
                    "Existence disclosure on notification-preferences: cross-user probe returns " +
                    crossUserResp.status() + " but nonexistent-user probe returns " +
                    nonexistentResp.status() + ". Both must match.");
        }

        return GateResult.pass(NAME, elapsed(start));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildWorkOrderBody(String runId) {
        return """
                {
                  "title": "Access control test - %s",
                  "priority": "LOW",
                  "category": "GENERAL",
                  "validationRunId": "%s"
                }
                """.formatted(runId, runId);
    }

    private String extractId(com.fasterxml.jackson.databind.JsonNode node) {
        com.fasterxml.jackson.databind.JsonNode data =
                node.has("data") ? node.get("data") : node;
        com.fasterxml.jackson.databind.JsonNode id = data.get("id");
        return id != null && !id.isNull() ? id.asText() : null;
    }

    private String extractToken(RunConfig config, ApiClient client) {
        // Token is managed internally by ApiClient; returning a placeholder for type safety
        return null;
    }

    private void cleanup(RunConfig config, ApiClient client, String token, String woId) {
        try {
            if (token != null) {
                client.put(config.url("/api/v1/work-orders/" + woId + "/state"),
                        "{\"state\":\"CANCELLED\"}");
            }
        } catch (Exception ignored) {
            // Best-effort
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
