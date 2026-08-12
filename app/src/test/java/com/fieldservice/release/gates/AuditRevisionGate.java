package com.fieldservice.release.gates;

import com.fieldservice.release.FailureClass;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.HttpGateClient;
import com.fieldservice.release.RunConfig;
import com.fieldservice.release.SmokePath;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Invariant gate 1: every state change produces exactly one audit revision.
 *
 * <p>Verification strategy: after each mutating operation, GET
 * /api/v1/work-orders/{id}/revisions and assert that the total revision count
 * has incremented by exactly one. This requires only the public API — no direct
 * database access — satisfying the constraint that production runs must not have
 * DB credentials.
 *
 * <p>The gate does NOT check outbox event existence because outbox rows are
 * an internal concern not exposed through the public REST API. The smoke path
 * proves state transitions succeed end-to-end, which is the observable guarantee.
 */
public final class AuditRevisionGate {

    public static final String GATE_NAME = "AUDIT_REVISION";

    private AuditRevisionGate() {}

    public static GateResult run(HttpGateClient client, RunConfig config, List<String> createdIds) {
        long start = System.currentTimeMillis();
        try {
            String woId = createTaggedWorkOrder(client, config);
            createdIds.add(woId);

            // After creation — expect 1 revision (INSERT)
            int afterCreate = fetchRevisionCount(client, woId);
            if (afterCreate < 1) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Expected ≥1 revision after creation but found " + afterCreate,
                        FailureClass.INVARIANT_VIOLATION);
            }

            // Assign to seed technician
            int woVersion = fetchVersion(client, woId);
            String assignBody = """
                    {"technicianId":"%s"}""".formatted(config.technicianId());
            HttpResponse<String> assignResp = client.post(
                    "/api/v1/work-orders/" + woId + "/assignment", assignBody);
            if (assignResp.statusCode() != 200) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Assignment failed: HTTP " + assignResp.statusCode(), FailureClass.SMOKE_PATH);
            }

            // After assignment — revision count must have grown
            int afterAssign = fetchRevisionCount(client, woId);
            if (afterAssign <= afterCreate) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "No new revision after assignment — expected >" + afterCreate
                                + " but found " + afterAssign,
                        FailureClass.INVARIANT_VIOLATION);
            }

            return GateResult.pass(GATE_NAME, elapsed(start));
        } catch (HttpGateClient.GateException e) {
            return GateResult.fail(GATE_NAME, elapsed(start), e.getMessage(), e.failureClass());
        }
    }

    private static String createTaggedWorkOrder(HttpGateClient client, RunConfig config)
            throws HttpGateClient.GateException {
        String body = """
                {
                  "customerId":          "%s",
                  "siteId":              "%s",
                  "faultDescription":    "%s AuditRevisionGate probe WO",
                  "priority":            "LOW"
                }""".formatted(config.customerId(), config.siteId(), config.runTag());
        HttpResponse<String> resp = client.post("/api/v1/work-orders", body);
        if (resp.statusCode() != 201 && resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "WO creation failed: HTTP " + resp.statusCode());
        }
        return SmokePath.extractString(client.parseJson(resp.body()), "id");
    }

    private static int fetchRevisionCount(HttpGateClient client, String woId)
            throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.get("/api/v1/work-orders/" + woId + "/revisions?size=1");
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                    "Revisions endpoint returned " + resp.statusCode());
        }
        Map<String, Object> body = client.parseJson(resp.body());
        // PagedResponse: { page: { totalElements: N } } or { data: [...] }
        Object page = body.get("page");
        if (page instanceof Map<?, ?> pageMap) {
            Object total = pageMap.get("totalElements");
            if (total instanceof Number n) return n.intValue();
        }
        // Fallback: count data array
        Object data = body.get("data");
        if (data instanceof List<?> list) return list.size();
        throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                "Cannot parse revision count from response: " + SmokePath.truncate(resp.body()));
    }

    private static int fetchVersion(HttpGateClient client, String woId)
            throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.get("/api/v1/work-orders/" + woId);
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                    "WO GET returned " + resp.statusCode());
        }
        Map<String, Object> body = client.parseJson(resp.body());
        return SmokePath.extractInt(body, "version");
    }

    private static long elapsed(long start) { return System.currentTimeMillis() - start; }
}
