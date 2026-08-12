package com.fieldservice.release;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin smoke path exercising basic reachability, actuator hardening, auth, and
 * the create-assign-transition happy path through the public API.
 *
 * <p>Each step has an implicit time budget enforced by the HTTP client timeout.
 * A step that hangs indefinitely will fail the client timeout (15 s) rather than
 * blocking the runner indefinitely.
 *
 * <p>This class does NOT cover dashboard freshness or KPI-staleness gating —
 * those are validated separately per scope boundary defined in the runbook.
 */
public final class SmokePath {

    public static final String GATE_NAME = "SMOKE_PATH";

    private SmokePath() {}

    /**
     * Runs all smoke steps and returns a result. Creates a work order tagged with the
     * run identifier; the caller is responsible for cleanup.
     *
     * @param client    authenticated gate client
     * @param config    run configuration
     * @param createdIds mutable list — any created work-order IDs are appended
     * @return gate result
     */
    public static GateResult run(HttpGateClient client, RunConfig config, List<String> createdIds) {
        long start = System.currentTimeMillis();
        try {
            checkHealth(client);
            checkActuatorHardening(client);
            checkRefresh(client);
            String woId = runWorkOrderPath(client, config);
            createdIds.add(woId);
            return GateResult.pass(GATE_NAME, System.currentTimeMillis() - start);
        } catch (HttpGateClient.GateException e) {
            return GateResult.fail(GATE_NAME, System.currentTimeMillis() - start,
                    e.getMessage(), FailureClass.SMOKE_PATH);
        }
    }

    private static void checkHealth(HttpGateClient client) throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.getUnauthenticated("/actuator/health");
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Health endpoint returned " + resp.statusCode());
        }
        String body = resp.body();
        if (!body.contains("\"UP\"") && !body.contains("UP")) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Health status is not UP: " + truncate(body));
        }
        HttpResponse<String> readiness = client.getUnauthenticated("/actuator/health/readiness");
        if (readiness.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Readiness endpoint returned " + readiness.statusCode());
        }
    }

    private static void checkActuatorHardening(HttpGateClient client) throws HttpGateClient.GateException {
        // Env, heapdump, and loggers must not be exposed
        for (String restricted : List.of("/actuator/env", "/actuator/heapdump", "/actuator/loggers")) {
            HttpResponse<String> resp = client.getUnauthenticated(restricted);
            if (resp.statusCode() != 404 && resp.statusCode() != 401 && resp.statusCode() != 403) {
                throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                        "Actuator endpoint " + restricted + " is unexpectedly reachable: HTTP "
                                + resp.statusCode());
            }
        }
    }

    private static void checkRefresh(HttpGateClient client) throws HttpGateClient.GateException {
        client.refresh();
    }

    private static String runWorkOrderPath(HttpGateClient client, RunConfig config)
            throws HttpGateClient.GateException {

        // Create a LOW-priority work order tagged with the run identifier
        String createBody = """
                {
                  "customerId":          "%s",
                  "siteId":              "%s",
                  "faultDescription":    "%s Automated smoke-path WO — safe to cancel",
                  "priority":            "LOW"
                }""".formatted(config.customerId(), config.siteId(), config.runTag());

        HttpResponse<String> createResp = client.post("/api/v1/work-orders", createBody);
        if (createResp.statusCode() != 201 && createResp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Work order creation failed: HTTP " + createResp.statusCode()
                            + " " + truncate(createResp.body()));
        }
        Map<String, Object> wo = client.parseJson(createResp.body());
        String woId = extractString(wo, "id");

        // Get recommendations (read-only, just assert 200)
        HttpResponse<String> recResp = client.get("/api/v1/work-orders/" + woId + "/recommendations");
        if (recResp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Recommendations endpoint returned " + recResp.statusCode());
        }

        // Assign to seed technician
        String assignBody = """
                {"technicianId":"%s"}""".formatted(config.technicianId());
        HttpResponse<String> assignResp = client.post(
                "/api/v1/work-orders/" + woId + "/assignment", assignBody);
        if (assignResp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Assignment failed: HTTP " + assignResp.statusCode()
                            + " " + truncate(assignResp.body()));
        }
        Map<String, Object> assigned = client.parseJson(assignResp.body());
        int version = extractInt(assigned, "workOrderVersion");

        // Transition: DEPART → EN_ROUTE
        String departBody = """
                {"event":"DEPART","expectedVersion":%d}""".formatted(version);
        HttpResponse<String> departResp = client.post(
                "/api/v1/work-orders/" + woId + "/transitions", departBody);
        if (departResp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "DEPART transition failed: HTTP " + departResp.statusCode()
                            + " " + truncate(departResp.body()));
        }

        return woId;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String extractString(Map<String, Object> map, String key) throws HttpGateClient.GateException {
        Object val = map.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                "Expected string field '" + key + "' in response but got: " + val);
    }

    static int extractInt(Map<String, Object> map, String key) throws HttpGateClient.GateException {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                "Expected numeric field '" + key + "' in response but got: " + val);
    }

    static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
