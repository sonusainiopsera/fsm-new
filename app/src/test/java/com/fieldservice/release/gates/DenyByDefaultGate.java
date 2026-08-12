package com.fieldservice.release.gates;

import com.fieldservice.release.FailureClass;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.HttpGateClient;
import com.fieldservice.release.RunConfig;
import com.fieldservice.release.SmokePath;

import java.net.http.HttpResponse;
import java.util.List;

/**
 * Invariant gate 4: deny by default with no existence disclosure.
 *
 * <p>Three sub-checks:
 * <ol>
 *   <li><strong>Unauthenticated probe</strong> — GET /api/v1/work-orders without a token
 *       must return 401.</li>
 *   <li><strong>Cross-role probe on a private WO</strong> — a WO created by the ADMIN
 *       validation account is fetched by the low-privilege CUSTOMER probe account.
 *       The response must be 403 or 404 (no existence disclosure); the body must NOT
 *       contain the work-order ID.</li>
 *   <li><strong>Cross-role probe on the revisions endpoint</strong> — the CUSTOMER probe
 *       must not be able to access the audit revisions for the same WO.</li>
 * </ol>
 */
public final class DenyByDefaultGate {

    public static final String GATE_NAME = "DENY_BY_DEFAULT";

    private DenyByDefaultGate() {}

    public static GateResult run(HttpGateClient client, RunConfig config, List<String> createdIds,
                                  String probeToken) {
        long start = System.currentTimeMillis();
        try {
            checkUnauthenticated(client);
            String woId = createProbeTarget(client, config, createdIds);
            checkCrossRoleProbe(client, woId, probeToken);
            checkCrossRoleRevisionProbe(client, woId, probeToken);
            return GateResult.pass(GATE_NAME, elapsed(start));
        } catch (HttpGateClient.GateException e) {
            return GateResult.fail(GATE_NAME, elapsed(start), e.getMessage(), e.failureClass());
        }
    }

    // ── Sub-check 1: unauthenticated probe ───────────────────────────────────

    private static void checkUnauthenticated(HttpGateClient client) throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.getUnauthenticated("/api/v1/work-orders");
        if (resp.statusCode() != 401) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "Unauthenticated GET /api/v1/work-orders returned "
                            + resp.statusCode() + " instead of 401");
        }
    }

    // ── Sub-check 2: cross-role probe on WO returns 403/404 ─────────────────

    private static String createProbeTarget(HttpGateClient client, RunConfig config,
                                            List<String> createdIds)
            throws HttpGateClient.GateException {
        String body = """
                {
                  "customerId":       "%s",
                  "siteId":           "%s",
                  "faultDescription": "%s DenyByDefaultGate probe WO",
                  "priority":         "LOW"
                }""".formatted(config.customerId(), config.siteId(), config.runTag());
        HttpResponse<String> resp = client.post("/api/v1/work-orders", body);
        if (resp.statusCode() != 201 && resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "WO creation failed: HTTP " + resp.statusCode());
        }
        String woId = SmokePath.extractString(client.parseJson(resp.body()), "id");
        createdIds.add(woId);
        return woId;
    }

    private static void checkCrossRoleProbe(HttpGateClient client, String woId, String probeToken)
            throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.getAs("/api/v1/work-orders/" + woId, probeToken);
        int status = resp.statusCode();
        // Must be 403 or 404 — never 200
        if (status == 200) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "CUSTOMER probe got 200 on WO " + woId + " — access control not enforced");
        }
        if (status != 403 && status != 404) {
            throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                    "Cross-role WO probe returned unexpected status " + status);
        }
        // Body must not contain the actual WO id (existence disclosure check)
        String responseBody = resp.body();
        if (responseBody != null && responseBody.contains(woId)) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "Cross-role probe response body contains the WO id — existence disclosure: "
                            + SmokePath.truncate(responseBody));
        }
    }

    private static void checkCrossRoleRevisionProbe(HttpGateClient client, String woId,
                                                     String probeToken)
            throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.getAs(
                "/api/v1/work-orders/" + woId + "/revisions", probeToken);
        int status = resp.statusCode();
        if (status == 200) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "CUSTOMER probe got 200 on revisions for WO " + woId
                            + " — audit access control not enforced");
        }
        if (status != 403 && status != 404) {
            throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                    "Cross-role revision probe returned unexpected status " + status);
        }
    }

    private static long elapsed(long start) { return System.currentTimeMillis() - start; }
}
