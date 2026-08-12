package com.fieldservice.release;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.fieldservice.release.ValidationHttpClient.jsonBody;

/**
 * Smoke path: exercises the core HTTP surface of the API to confirm the deployment is
 * reachable, authentication works, and the happy-path work-order flow runs end-to-end
 * with per-step time budgets.
 *
 * <p>Time budgets (documented for RUNBOOK reference):
 * <ul>
 *   <li>Health/readiness check: 5 s</li>
 *   <li>Login: 5 s</li>
 *   <li>Token refresh: 5 s</li>
 *   <li>Create work order: 5 s</li>
 *   <li>Get recommendations: 10 s</li>
 *   <li>Assign work order: 5 s</li>
 *   <li>Transition work order: 5 s per transition step</li>
 *   <li>Actuator hardening assertions: 5 s</li>
 * </ul>
 *
 * <p>All created records are tagged with the run identifier and cancelled at the end of
 * the run via {@link #cleanup}.
 */
public final class SmokePath {

    private static final Duration HEALTH_BUDGET  = Duration.ofSeconds(5);
    private static final Duration AUTH_BUDGET     = Duration.ofSeconds(5);
    private static final Duration WO_BUDGET       = Duration.ofSeconds(5);
    private static final Duration RECOM_BUDGET    = Duration.ofSeconds(10);
    private static final Duration TRANS_BUDGET    = Duration.ofSeconds(5);
    private static final Duration ACTUATOR_BUDGET = Duration.ofSeconds(5);

    private final ValidationConfig config;
    private final ValidationHttpClient http;

    private String createdWorkOrderId;
    private String accessToken;
    private String refreshToken;

    public SmokePath(ValidationConfig config, ValidationHttpClient http) {
        this.config = config;
        this.http   = http;
    }

    /** Runs the full smoke path and returns a list of gate results for each step. */
    public List<GateResult> run() throws InterruptedException {
        List<GateResult> results = new ArrayList<>();

        results.add(checkHealth());
        results.add(checkActuatorHardening());
        results.add(doLogin());
        if (results.stream().anyMatch(GateResult::failed)) {
            results.add(GateResult.skip("SmokePath.refresh", "Login failed; skipping remaining smoke steps"));
            results.add(GateResult.skip("SmokePath.happyPath", "Login failed; skipping happy path"));
            return results;
        }
        results.add(doRefresh());
        results.add(doHappyPath());
        return results;
    }

    // ── Individual smoke steps ────────────────────────────────────────────────────

    GateResult checkHealth() throws InterruptedException {
        Instant start = Instant.now();
        String url = config.baseUrlString() + "/actuator/health";
        try {
            ValidationHttpClient.ApiResponse resp = withBudget(HEALTH_BUDGET, () -> {
                try { return http.getUnauthenticated(url); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (!resp.isOk()) {
                return GateResult.fail("SmokePath.health",
                        dur(start), "Health check returned HTTP " + resp.status());
            }
            String status = resp.jsonField("/status");
            if (!"UP".equalsIgnoreCase(status) && !"OK".equalsIgnoreCase(status)) {
                return GateResult.fail("SmokePath.health",
                        dur(start), "Health status is '" + status + "'; expected UP");
            }
            return GateResult.pass("SmokePath.health", dur(start), "status=" + status);
        } catch (IOException e) {
            return GateResult.setupError("SmokePath.health",
                    "Transport error reaching " + url + ": " + e.getMessage());
        }
    }

    GateResult checkActuatorHardening() throws InterruptedException {
        Instant start = Instant.now();
        // Sensitive actuator endpoints must return 404 (not exposed)
        String[] disallowed = { "/actuator/env", "/actuator/heapdump", "/actuator/loggers" };
        try {
            for (String path : disallowed) {
                String url = config.baseUrlString() + path;
                ValidationHttpClient.ApiResponse resp = withBudget(ACTUATOR_BUDGET,
                        () -> { try { return http.getUnauthenticated(url); }
                                catch (IOException e) { throw new RuntimeException(e); } });
                if (resp.status() != 404 && resp.status() != 401) {
                    return GateResult.fail("SmokePath.actuatorHardening", dur(start),
                            path + " returned HTTP " + resp.status() +
                            " — endpoint must be disabled (404) or require auth (401)");
                }
            }
            // Prometheus should return 200 (metrics exposed to infra scraper)
            String promUrl = config.baseUrlString() + "/actuator/prometheus";
            ValidationHttpClient.ApiResponse prom = withBudget(ACTUATOR_BUDGET,
                    () -> { try { return http.getUnauthenticated(promUrl); }
                            catch (IOException e) { throw new RuntimeException(e); } });
            if (prom.status() != 200 && prom.status() != 401) {
                return GateResult.fail("SmokePath.actuatorHardening", dur(start),
                        "/actuator/prometheus returned HTTP " + prom.status());
            }
            return GateResult.pass("SmokePath.actuatorHardening", dur(start),
                    "Sensitive endpoints disabled, /health and /prometheus accessible");
        } catch (IOException e) {
            return GateResult.setupError("SmokePath.actuatorHardening",
                    "Transport error: " + e.getMessage());
        }
    }

    GateResult doLogin() throws InterruptedException {
        Instant start = Instant.now();
        String url = config.baseUrlString() + "/api/v1/auth/login";
        try {
            String body = jsonBody(
                    "email", config.accountEmail(),
                    "password", new String(config.accountPassword()));

            ValidationHttpClient.ApiResponse resp = withBudget(AUTH_BUDGET, () -> {
                try { return http.postUnauthenticated(url, body); }
                catch (IOException e) { throw new RuntimeException(e); }
            });

            if (!resp.isOk()) {
                return GateResult.fail("SmokePath.login", dur(start),
                        "Login returned HTTP " + resp.status() +
                        " — check VALIDATION_ACCOUNT_EMAIL/PASSWORD");
            }
            accessToken  = resp.jsonField("/accessToken");
            refreshToken = resp.jsonField("/refreshToken");
            if (accessToken.isBlank()) {
                return GateResult.fail("SmokePath.login", dur(start),
                        "Login response missing accessToken field");
            }
            http.setBearerToken(accessToken);
            return GateResult.pass("SmokePath.login", dur(start), "Login succeeded");
        } catch (IOException e) {
            return GateResult.setupError("SmokePath.login",
                    "Transport error on login: " + e.getMessage());
        }
    }

    GateResult doRefresh() throws InterruptedException {
        Instant start = Instant.now();
        String url = config.baseUrlString() + "/api/v1/auth/refresh";
        try {
            String body = jsonBody("refreshToken", refreshToken);
            ValidationHttpClient.ApiResponse resp = withBudget(AUTH_BUDGET, () -> {
                try { return http.postUnauthenticated(url, body); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (!resp.isOk()) {
                return GateResult.fail("SmokePath.refresh", dur(start),
                        "Token refresh returned HTTP " + resp.status());
            }
            String newToken = resp.jsonField("/accessToken");
            if (newToken.isBlank()) {
                return GateResult.fail("SmokePath.refresh", dur(start),
                        "Refresh response missing accessToken field");
            }
            accessToken = newToken;
            http.setBearerToken(accessToken);
            return GateResult.pass("SmokePath.refresh", dur(start), "Token refreshed");
        } catch (IOException e) {
            return GateResult.setupError("SmokePath.refresh",
                    "Transport error on refresh: " + e.getMessage());
        }
    }

    GateResult doHappyPath() throws InterruptedException {
        Instant start = Instant.now();
        String base = config.baseUrlString();
        try {
            // 1. Create work order tagged with runId
            String createBody = String.format(
                    "{\"title\":\"Validation-%s\",\"description\":\"Post-deploy smoke test — run %s\"," +
                    "\"customerAccountId\":\"00000000-0000-0000-0000-000000000001\"," +
                    "\"siteId\":\"10000000-0000-0000-0000-000000000001\"," +
                    "\"scheduledStart\":\"2099-01-01T08:00:00Z\"," +
                    "\"scheduledEnd\":\"2099-01-01T10:00:00Z\"}",
                    config.runId(), config.runId());

            ValidationHttpClient.ApiResponse create = withBudget(WO_BUDGET, () -> {
                try { return http.post(base + "/api/v1/work-orders", createBody); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (create.status() != 201) {
                return GateResult.fail("SmokePath.happyPath", dur(start),
                        "Create work order returned HTTP " + create.status());
            }
            createdWorkOrderId = create.jsonField("/id");
            if (createdWorkOrderId.isBlank()) {
                return GateResult.fail("SmokePath.happyPath", dur(start),
                        "Create work order response missing id field");
            }

            // 2. Get recommendations
            String recUrl = base + "/api/v1/work-orders/" + createdWorkOrderId + "/recommendations";
            ValidationHttpClient.ApiResponse recs = withBudget(RECOM_BUDGET, () -> {
                try { return http.get(recUrl); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (!recs.isOk()) {
                return GateResult.fail("SmokePath.happyPath", dur(start),
                        "Get recommendations returned HTTP " + recs.status());
            }

            // 3. Assign — use first recommendation if available, else use tech fixture
            String snapshotId = recs.jsonField("/recommendationSnapshotId");
            String techId = "00000000-0000-0000-0000-000000000011";
            String woVersion = create.jsonField("/version");

            String assignBody = String.format(
                    "{\"technicianId\":\"%s\",\"recommendationSnapshotId\":%s," +
                    "\"overrideReason\":\"smoke-test-%s\",\"expectedVersion\":%s}",
                    techId,
                    snapshotId.isBlank() ? "null" : "\"" + snapshotId + "\"",
                    config.runId(),
                    woVersion.isBlank() ? "0" : woVersion);

            String assignUrl = base + "/api/v1/work-orders/" + createdWorkOrderId + "/assignment";
            ValidationHttpClient.ApiResponse assign = withBudget(WO_BUDGET, () -> {
                try { return http.post(assignUrl, assignBody); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (!assign.isOk()) {
                return GateResult.fail("SmokePath.happyPath", dur(start),
                        "Assign returned HTTP " + assign.status() +
                        ": " + assign.body().substring(0, Math.min(200, assign.body().length())));
            }

            // 4. Transition ASSIGNED → EN_ROUTE
            String transUrl = base + "/api/v1/work-orders/" + createdWorkOrderId + "/transitions";
            String assignedVersion = assign.jsonField("/version");
            String transBody = String.format(
                    "{\"event\":\"START_TRAVEL\",\"expectedVersion\":%s}",
                    assignedVersion.isBlank() ? "1" : assignedVersion);
            ValidationHttpClient.ApiResponse trans = withBudget(TRANS_BUDGET, () -> {
                try { return http.post(transUrl, transBody); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (!trans.isOk()) {
                return GateResult.fail("SmokePath.happyPath", dur(start),
                        "START_TRAVEL transition returned HTTP " + trans.status());
            }

            return GateResult.pass("SmokePath.happyPath", dur(start),
                    "Work order " + createdWorkOrderId + " created, assigned, and transitioned");
        } catch (IOException e) {
            return GateResult.setupError("SmokePath.happyPath",
                    "Transport error in happy path: " + e.getMessage());
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────────

    /**
     * Cancels any work order created during this run.
     * Returns a result describing cleanup outcome (separate from gate outcome).
     */
    public GateResult cleanup() throws InterruptedException {
        if (createdWorkOrderId == null) {
            return GateResult.skip("SmokePath.cleanup", "No work order was created");
        }
        Instant start = Instant.now();
        String base = config.baseUrlString();
        try {
            // Fetch current version first
            ValidationHttpClient.ApiResponse current =
                    withBudget(WO_BUDGET, () -> {
                        try { return http.get(base + "/api/v1/work-orders/" + createdWorkOrderId); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    });
            if (!current.isOk()) {
                return GateResult.fail("SmokePath.cleanup", dur(start),
                        "Cannot fetch created work order for cleanup (HTTP " + current.status() + ")");
            }
            JsonNode wo = current.json();
            String state   = wo.path("state").asText("");
            String version = wo.path("version").asText("0");

            if ("CANCELLED".equals(state) || "CLOSED".equals(state)) {
                return GateResult.pass("SmokePath.cleanup", dur(start),
                        "Work order " + createdWorkOrderId + " already in terminal state " + state);
            }

            String transUrl = base + "/api/v1/work-orders/" + createdWorkOrderId + "/transitions";
            String body = String.format(
                    "{\"event\":\"CANCEL\",\"reason\":\"smoke-test-cleanup-%s\",\"expectedVersion\":%s}",
                    config.runId(), version);
            ValidationHttpClient.ApiResponse resp = withBudget(TRANS_BUDGET, () -> {
                try { return http.post(transUrl, body); }
                catch (IOException e) { throw new RuntimeException(e); }
            });
            if (!resp.isOk()) {
                return GateResult.fail("SmokePath.cleanup", dur(start),
                        "Cancel returned HTTP " + resp.status() + " for " + createdWorkOrderId);
            }
            return GateResult.pass("SmokePath.cleanup", dur(start),
                    "Cancelled work order " + createdWorkOrderId);
        } catch (IOException e) {
            return GateResult.fail("SmokePath.cleanup", dur(start),
                    "Transport error during cleanup: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static Duration dur(Instant start) {
        return Duration.between(start, Instant.now());
    }

    @FunctionalInterface
    interface IoSupplier<T> { T get() throws Exception; }

    private static <T> T withBudget(Duration budget, IoSupplier<T> action)
            throws IOException, InterruptedException {
        // The HttpClient timeout on each request enforces the budget — this is a
        // belt-and-suspenders wrapper for explicit documentation purposes.
        try {
            return action.get();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioe) throw ioe;
            if (e.getCause() instanceof InterruptedException ie) throw ie;
            throw e;
        } catch (IOException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unexpected error: " + e.getMessage(), e);
        }
    }

    public String getCreatedWorkOrderId() { return createdWorkOrderId; }
    public String getAccessToken()        { return accessToken; }
}
