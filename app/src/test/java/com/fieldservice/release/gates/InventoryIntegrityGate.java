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
 * Invariant gate 2: stock never goes negative.
 *
 * <p>Test plan:
 * <ol>
 *   <li>Read the current balance for the designated validation part.</li>
 *   <li>Attempt to consume (currentBalance + 99) units — must return 422 and the
 *       balance must be unchanged.</li>
 *   <li>Consume exactly 1 unit — must succeed and the balance must be exactly
 *       (originalBalance − 1).</li>
 * </ol>
 *
 * <p>Parts consumption requires an IN_PROGRESS work order. The gate creates a
 * temporary WO, advances it to IN_PROGRESS, performs the consumption tests, and
 * registers the WO for cleanup.
 *
 * <p>If the validation part has zero balance (depleted in a long-lived environment),
 * the gate returns ENVIRONMENT_SETUP so operators know to replenish, not FAIL.
 */
public final class InventoryIntegrityGate {

    public static final String GATE_NAME = "INVENTORY_INTEGRITY";

    private InventoryIntegrityGate() {}

    public static GateResult run(HttpGateClient client, RunConfig config, List<String> createdIds) {
        long start = System.currentTimeMillis();
        try {
            // ── Step 1: read starting balance ─────────────────────────────
            int originalBalance = fetchBalance(client, config.partId(), config.stockLocationId());
            if (originalBalance <= 0) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Validation part " + config.partId() + " has zero stock at location "
                                + config.stockLocationId() + ". Replenish before running.",
                        FailureClass.ENVIRONMENT_SETUP);
            }

            // ── Step 2: advance a WO to IN_PROGRESS for parts consumption ──
            String woId = createAndAdvanceToInProgress(client, config);
            createdIds.add(woId);

            // ── Step 3: attempt over-consumption — must be refused with 422 ─
            int overQty = originalBalance + 99;
            String overBody = """
                    {"stockLocationId":"%s","lines":[{"partId":"%s","quantity":%d,"reasonCode":"GATE_TEST"}]}
                    """.formatted(config.stockLocationId(), config.partId(), overQty);
            HttpResponse<String> overResp = client.post(
                    "/api/v1/work-orders/" + woId + "/parts", overBody);
            if (overResp.statusCode() != 422) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Over-consumption (" + overQty + " units) returned HTTP "
                                + overResp.statusCode() + " instead of 422",
                        FailureClass.INVARIANT_VIOLATION);
            }
            // Balance must be unchanged after refusal
            int balanceAfterRefusal = fetchBalance(client, config.partId(), config.stockLocationId());
            if (balanceAfterRefusal != originalBalance) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Balance changed after refused over-consumption: was "
                                + originalBalance + ", now " + balanceAfterRefusal,
                        FailureClass.INVARIANT_VIOLATION);
            }

            // ── Step 4: consume exactly 1 unit — must succeed ───────────────
            String consumeBody = """
                    {"stockLocationId":"%s","lines":[{"partId":"%s","quantity":1,"reasonCode":"GATE_TEST"}]}
                    """.formatted(config.stockLocationId(), config.partId());
            HttpResponse<String> consumeResp = client.post(
                    "/api/v1/work-orders/" + woId + "/parts", consumeBody);
            if (consumeResp.statusCode() != 200) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Consumption of 1 unit failed: HTTP " + consumeResp.statusCode()
                                + " " + SmokePath.truncate(consumeResp.body()),
                        FailureClass.INVARIANT_VIOLATION);
            }
            int balanceAfterConsume = fetchBalance(client, config.partId(), config.stockLocationId());
            if (balanceAfterConsume != originalBalance - 1) {
                return GateResult.fail(GATE_NAME, elapsed(start),
                        "Balance after consuming 1 unit is " + balanceAfterConsume
                                + "; expected " + (originalBalance - 1),
                        FailureClass.INVARIANT_VIOLATION);
            }

            return GateResult.pass(GATE_NAME, elapsed(start));
        } catch (HttpGateClient.GateException e) {
            return GateResult.fail(GATE_NAME, elapsed(start), e.getMessage(), e.failureClass());
        }
    }

    private static int fetchBalance(HttpGateClient client, String partId, String locationId)
            throws HttpGateClient.GateException {
        HttpResponse<String> resp = client.get(
                "/api/v1/inventory/stock?partId=" + partId + "&locationId=" + locationId + "&size=1");
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.TRANSPORT,
                    "Stock balance query returned " + resp.statusCode());
        }
        Map<String, Object> body = client.parseJson(resp.body());
        Object data = body.get("data");
        if (data instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> entry) {
                Object qty = entry.get("quantityOnHand");
                if (qty instanceof Number n) return n.intValue();
            }
        }
        return 0;
    }

    private static String createAndAdvanceToInProgress(HttpGateClient client, RunConfig config)
            throws HttpGateClient.GateException {
        // Create WO
        String createBody = """
                {
                  "customerId":       "%s",
                  "siteId":           "%s",
                  "faultDescription": "%s InventoryIntegrityGate probe WO",
                  "priority":         "LOW"
                }""".formatted(config.customerId(), config.siteId(), config.runTag());
        HttpResponse<String> cr = client.post("/api/v1/work-orders", createBody);
        if (cr.statusCode() != 201 && cr.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "WO creation failed: HTTP " + cr.statusCode());
        }
        Map<String, Object> wo = client.parseJson(cr.body());
        String woId    = SmokePath.extractString(wo, "id");
        int    version = SmokePath.extractInt(wo, "version");

        // Assign
        String assignBody = """
                {"technicianId":"%s"}""".formatted(config.technicianId());
        HttpResponse<String> ar = client.post("/api/v1/work-orders/" + woId + "/assignment", assignBody);
        if (ar.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    "Assignment failed: HTTP " + ar.statusCode());
        }
        version = SmokePath.extractInt(client.parseJson(ar.body()), "workOrderVersion");

        // DEPART → EN_ROUTE
        version = transition(client, woId, "DEPART", version);
        // START → IN_PROGRESS
        transition(client, woId, "START", version);
        return woId;
    }

    private static int transition(HttpGateClient client, String woId, String event, int version)
            throws HttpGateClient.GateException {
        String body = """
                {"event":"%s","expectedVersion":%d}""".formatted(event, version);
        HttpResponse<String> resp = client.post("/api/v1/work-orders/" + woId + "/transitions", body);
        if (resp.statusCode() != 200) {
            throw new HttpGateClient.GateException(FailureClass.SMOKE_PATH,
                    event + " transition failed: HTTP " + resp.statusCode()
                            + " " + SmokePath.truncate(resp.body()));
        }
        return SmokePath.extractInt(client.parseJson(resp.body()), "version");
    }

    private static long elapsed(long start) { return System.currentTimeMillis() - start; }
}
