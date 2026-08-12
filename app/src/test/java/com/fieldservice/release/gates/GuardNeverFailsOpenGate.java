package com.fieldservice.release.gates;

import com.fieldservice.release.FailureClass;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.HttpGateClient;
import com.fieldservice.release.RunConfig;
import com.fieldservice.release.SmokePath;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Invariant gate 3: guards never fail open.
 *
 * <p>Three sub-checks, each asserting a different guard refuses an illegal request:
 * <ol>
 *   <li><strong>Illegal lifecycle transition</strong> — attempt to COMPLETE a NEW work order
 *       (not a legal transition); must return 409.</li>
 *   <li><strong>Completion without labour time</strong> — advance a WO to IN_PROGRESS and
 *       attempt COMPLETE without recording labour time; must return 422.</li>
 *   <li><strong>Assignment to expired-certification technician</strong> — create a WO
 *       requiring a certification that the target technician holds only as an expired entry;
 *       attempt assignment even as ADMIN; must return 422.</li>
 * </ol>
 */
public final class GuardNeverFailsOpenGate {

    public static final String GATE_NAME = "GUARD_NEVER_FAILS_OPEN";

    private GuardNeverFailsOpenGate() {}

    public static GateResult run(HttpGateClient client, RunConfig config, List<String> createdIds) {
        long start = System.currentTimeMillis();
        try {
            checkIllegalTransition(client, config, createdIds);
            checkCompleteWithoutLabour(client, config, createdIds);
            checkExpiredCertAssignment(client, config, createdIds);
            return GateResult.pass(GATE_NAME, elapsed(start));
        } catch (HttpGateClient.GateException e) {
            return GateResult.fail(GATE_NAME, elapsed(start), e.getMessage(), e.failureClass());
        }
    }

    // ── Sub-check 1: illegal lifecycle transition returns 409 ────────────────

    private static void checkIllegalTransition(HttpGateClient client, RunConfig config,
                                               List<String> createdIds)
            throws HttpGateClient.GateException {
        // Create a NEW work order and immediately attempt COMPLETE (illegal)
        String woId = createWo(client, config, "GuardGate-illegalTransition");
        createdIds.add(woId);
        int version = fetchVersion(client, woId);

        String body = """
                {"event":"COMPLETE","expectedVersion":%d}""".formatted(version);
        HttpResponse<String> resp = client.post("/api/v1/work-orders/" + woId + "/transitions", body);
        if (resp.statusCode() != 409) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "Illegal transition COMPLETE from NEW returned " + resp.statusCode()
                            + " instead of 409 — guard may have failed open");
        }
    }

    // ── Sub-check 2: COMPLETE without labour time returns 422 ────────────────

    private static void checkCompleteWithoutLabour(HttpGateClient client, RunConfig config,
                                                    List<String> createdIds)
            throws HttpGateClient.GateException {
        String woId = createWo(client, config, "GuardGate-completeNoLabour");
        createdIds.add(woId);

        // Advance to IN_PROGRESS
        int version = advanceToInProgress(client, woId, config.technicianId());

        // Attempt COMPLETE without any labour time recorded — must be 422
        String body = """
                {"event":"COMPLETE","expectedVersion":%d}""".formatted(version);
        HttpResponse<String> resp = client.post("/api/v1/work-orders/" + woId + "/transitions", body);
        if (resp.statusCode() != 422) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "COMPLETE without labour time returned " + resp.statusCode()
                            + " instead of 422 — guard may have failed open");
        }
    }

    // ── Sub-check 3: expired-certification assignment returns 422 ────────────

    private static void checkExpiredCertAssignment(HttpGateClient client, RunConfig config,
                                                    List<String> createdIds)
            throws HttpGateClient.GateException {
        // Create a WO that requires the expired certification code
        String certBody = """
                {
                  "customerId":               "%s",
                  "siteId":                   "%s",
                  "faultDescription":         "%s GuardGate-expiredCert WO",
                  "priority":                 "LOW",
                  "requiredCertificationCodes": ["%s"]
                }""".formatted(config.customerId(), config.siteId(),
                config.runTag(), config.expiredCertCode());
        HttpResponse<String> cr = client.post("/api/v1/work-orders", certBody);
        if (cr.statusCode() != 201 && cr.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "WO creation with cert requirement failed: HTTP " + cr.statusCode()
                            + " " + SmokePath.truncate(cr.body()));
        }
        String woId = SmokePath.extractString(client.parseJson(cr.body()), "id");
        createdIds.add(woId);

        // Attempt to assign to the technician whose cert is expired — must return 422
        String assignBody = """
                {"technicianId":"%s"}""".formatted(config.expiredCertTechId());
        HttpResponse<String> resp = client.post(
                "/api/v1/work-orders/" + woId + "/assignment", assignBody);
        if (resp.statusCode() != 422) {
            throw new HttpGateClient.GateException(FailureClass.INVARIANT_VIOLATION,
                    "Assignment of expired-cert technician returned "
                            + resp.statusCode() + " instead of 422 — guard may have failed open. "
                            + "technician=" + config.expiredCertTechId()
                            + " cert=" + config.expiredCertCode());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String createWo(HttpGateClient client, RunConfig config, String label)
            throws HttpGateClient.GateException {
        String body = """
                {
                  "customerId":       "%s",
                  "siteId":           "%s",
                  "faultDescription": "%s %s",
                  "priority":         "LOW"
                }""".formatted(config.customerId(), config.siteId(), config.runTag(), label);
        HttpResponse<String> resp = client.post("/api/v1/work-orders", body);
        if (resp.statusCode() != 201 && resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "WO creation failed: HTTP " + resp.statusCode());
        }
        return SmokePath.extractString(client.parseJson(resp.body()), "id");
    }

    private static int fetchVersion(HttpGateClient client, String woId)
            throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.get("/api/v1/work-orders/" + woId);
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                    "WO GET returned " + resp.statusCode());
        }
        return SmokePath.extractInt(client.parseJson(resp.body()), "version");
    }

    private static int advanceToInProgress(HttpGateClient client, String woId, String techId)
            throws HttpGateClient.GateException {
        String assignBody = """
                {"technicianId":"%s"}""".formatted(techId);
        HttpResponse<String> ar = client.post("/api/v1/work-orders/" + woId + "/assignment", assignBody);
        if (ar.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Assignment failed: HTTP " + ar.statusCode());
        }
        int version = SmokePath.extractInt(client.parseJson(ar.body()), "workOrderVersion");
        version = doTransition(client, woId, "DEPART", version);
        return doTransition(client, woId, "START", version);
    }

    private static int doTransition(HttpGateClient client, String woId, String event, int version)
            throws HttpGateClient.GateException {
        String body = """
                {"event":"%s","expectedVersion":%d}""".formatted(event, version);
        HttpResponse<String> resp = client.post("/api/v1/work-orders/" + woId + "/transitions", body);
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    event + " transition returned " + resp.statusCode()
                            + " " + SmokePath.truncate(resp.body()));
        }
        return SmokePath.extractInt(client.parseJson(resp.body()), "version");
    }

    private static long elapsed(long start) { return System.currentTimeMillis() - start; }
}
