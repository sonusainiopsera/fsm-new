package com.fieldservice.release.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fieldservice.release.ApiClient;
import com.fieldservice.release.Gate;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.RunConfig;

/**
 * Invariant gate 2: stock never goes negative and consumption is all-or-nothing.
 *
 * <p>Assertions:
 * <ol>
 *   <li>Over-consumption (quantity &gt; available) is refused with a non-2xx response.</li>
 *   <li>The balance is unchanged after a refused consumption.</li>
 *   <li>A successful consumption decrements the balance by exactly the consumed quantity.</li>
 * </ol>
 *
 * <p>Uses a designated validation part whose stock is replenished by the fixture.
 * If the part is missing or out of stock, returns {@link com.fieldservice.release.GateStatus#SETUP_ERROR}
 * rather than a gate failure.
 */
public class InventoryIntegrityGate implements Gate {

    private static final String NAME = "inventory-integrity-gate";

    // The designated validation part — must exist in the fixtures with a known balance
    static final String VALIDATION_PART_SKU = "VAL-PART-001";

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

        try {
            // ── 1. Verify the validation part exists and read its current balance ──
            ApiClient.ApiResponse partResp = client.get(
                    config.url("/api/v1/inventory/parts?sku=" + VALIDATION_PART_SKU));

            if (partResp.status() == 404 || partResp.status() == 405) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Inventory API not available (status " + partResp.status() + "). " +
                        "Deploy the full application stack first.");
            }
            if (!partResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Parts lookup returned " + partResp.status());
            }

            JsonNode partJson = partResp.json(client.mapper());
            String partId = extractPartId(partJson);
            if (partId == null) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Validation part '" + VALIDATION_PART_SKU + "' not found in inventory. " +
                        "Run the seed fixture to create it before executing the suite.");
            }

            int availableQty = extractBalance(partJson);
            if (availableQty < 1) {
                return GateResult.setupError(NAME, elapsed(start),
                        "Validation part '" + VALIDATION_PART_SKU + "' has zero stock (qty=" +
                        availableQty + "). Replenish before running the suite.");
            }

            // ── 2. Assert over-consumption is refused ─────────────────────────
            int overQty = availableQty + 999;
            ApiClient.ApiResponse overResp = client.post(
                    config.url("/api/v1/inventory/consume"),
                    buildConsumeBody(partId, overQty, config.runId()));

            if (overResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Over-consumption of " + overQty + " units (available=" + availableQty +
                        ") was accepted with status " + overResp.status() +
                        ". Stock invariant violated: consumption must be refused.");
            }
            // Expect 409 (conflict) or 422 (unprocessable)
            if (overResp.status() != 409 && overResp.status() != 422 && overResp.status() != 400) {
                return GateResult.fail(NAME, elapsed(start),
                        "Over-consumption refused with unexpected status " + overResp.status() +
                        "; expected 409 or 422.");
            }

            // ── 3. Assert balance unchanged after refusal ─────────────────────
            ApiClient.ApiResponse balanceAfterRefusal = client.get(
                    config.url("/api/v1/inventory/parts?sku=" + VALIDATION_PART_SKU));
            int balanceAfterRefusal2 = extractBalance(balanceAfterRefusal.json(client.mapper()));

            if (balanceAfterRefusal2 != availableQty) {
                return GateResult.fail(NAME, elapsed(start),
                        "Balance changed after refused consumption: was " + availableQty +
                        ", now " + balanceAfterRefusal2 + ". Partial write occurred.");
            }

            // ── 4. Assert successful consumption decrements correctly ──────────
            int consumeQty = 1;
            ApiClient.ApiResponse consumeResp = client.post(
                    config.url("/api/v1/inventory/consume"),
                    buildConsumeBody(partId, consumeQty, config.runId()));

            if (!consumeResp.is2xx()) {
                return GateResult.fail(NAME, elapsed(start),
                        "Valid consumption of 1 unit was rejected with status " +
                        consumeResp.status() + ". Body: " + truncate(consumeResp.body()));
            }

            ApiClient.ApiResponse balanceAfterConsume = client.get(
                    config.url("/api/v1/inventory/parts?sku=" + VALIDATION_PART_SKU));
            int newBalance = extractBalance(balanceAfterConsume.json(client.mapper()));

            int expectedBalance = availableQty - consumeQty;
            if (newBalance != expectedBalance) {
                return GateResult.fail(NAME, elapsed(start),
                        "Balance after consuming " + consumeQty + " unit(s) is " + newBalance +
                        "; expected " + expectedBalance +
                        " (was " + availableQty + "). Decrement is incorrect.");
            }

            return GateResult.pass(NAME, elapsed(start));

        } catch (ApiClient.TransportException e) {
            return GateResult.transportError(NAME, elapsed(start),
                    "Transport error: " + e.getMessage());
        }
        // Note: the consumed unit is intentionally not "returned" — the fixture replenishes
        // on next environment setup. A replenishment endpoint call could be added here once
        // the inventory module exposes one.
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildConsumeBody(String partId, int quantity, String runId) {
        return """
                {
                  "partId": "%s",
                  "quantity": %d,
                  "reference": "validation-run-%s"
                }
                """.formatted(partId, quantity, runId);
    }

    private String extractPartId(JsonNode json) {
        JsonNode data = json.has("data") ? json.get("data") : json;
        if (data.isArray() && !data.isEmpty()) {
            data = data.get(0);
        }
        JsonNode id = data.get("id");
        return id != null && !id.isNull() ? id.asText() : null;
    }

    private int extractBalance(JsonNode json) {
        JsonNode data = json.has("data") ? json.get("data") : json;
        if (data.isArray() && !data.isEmpty()) {
            data = data.get(0);
        }
        JsonNode qty = data.path("quantityOnHand");
        if (qty.isMissingNode()) qty = data.path("balance");
        return qty.asInt(-1);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
