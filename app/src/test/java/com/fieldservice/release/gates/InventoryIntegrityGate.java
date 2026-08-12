package com.fieldservice.release.gates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fieldservice.release.GateResult;
import com.fieldservice.release.ValidationConfig;
import com.fieldservice.release.ValidationHttpClient;
import com.fieldservice.release.ValidationHttpClient.ApiResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Invariant gate 2: stock never goes negative.
 *
 * <p>Asserts three sub-invariants:
 * <ol>
 *   <li>An over-consumption attempt is refused (HTTP 422 or 409).</li>
 *   <li>The stock balance is unchanged after a refused consumption attempt.</li>
 *   <li>A successful consumption decrements the balance by exactly the consumed quantity.</li>
 * </ol>
 *
 * <p>Uses the designated validation part — a known-ID part whose balance is regularly
 * replenished in non-production environments. If the balance is zero this gate returns
 * SETUP_ERROR rather than FAIL to distinguish a stock-management issue from an invariant
 * violation.
 *
 * <p>Failure interpretation (RUNBOOK): FAIL indicates the stock floor guard has been
 * removed or bypassed. SETUP_ERROR indicates the validation part needs replenishment.
 * FAIL is an immediate-rollback trigger; SETUP_ERROR is an ops task.
 */
public final class InventoryIntegrityGate {

    /** Designated validation part ID — must exist in all non-production environments. */
    public static final String VALIDATION_PART_ID = "00000000-0000-0000-0000-000000000099";

    private final ValidationConfig config;
    private final ValidationHttpClient http;

    public InventoryIntegrityGate(ValidationConfig config, ValidationHttpClient http) {
        this.config = config;
        this.http   = http;
    }

    public GateResult run() throws InterruptedException {
        if (config.dryRun()) {
            return GateResult.skip(getClass().getSimpleName(),
                    "Dry-run mode: inventory gate requires write operations");
        }

        Instant start = Instant.now();
        String base = config.baseUrlString();

        try {
            // 1. Read current balance
            long initialBalance = fetchBalance(base);
            if (initialBalance < 0) {
                // fetchBalance returns -1 on error (setup problem)
                return GateResult.setupError(getClass().getSimpleName(),
                        "Cannot read stock balance for validation part " + VALIDATION_PART_ID +
                        " — ensure the part exists in this environment");
            }
            if (initialBalance == 0) {
                return GateResult.setupError(getClass().getSimpleName(),
                        "Validation part " + VALIDATION_PART_ID +
                        " has zero stock — replenish before running this gate");
            }

            // 2. Attempt over-consumption (balance + 1) — must be refused
            long overConsume = initialBalance + 1;
            ApiResponse overResp = tryConsume(base, overConsume, config.runId() + "-over");
            if (overResp.isOk()) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Over-consumption of " + overConsume + " units was accepted (HTTP " +
                        overResp.status() + ") — stock floor invariant violated");
            }
            if (overResp.status() != 422 && overResp.status() != 409 && overResp.status() != 400) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Over-consumption returned unexpected HTTP " + overResp.status() +
                        " (expected 4xx refusal)");
            }

            // 3. Balance must be unchanged after the refused attempt
            long balanceAfterRefusal = fetchBalance(base);
            if (balanceAfterRefusal != initialBalance) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Balance changed from " + initialBalance + " to " + balanceAfterRefusal +
                        " after refused over-consumption — partial write leaked");
            }

            // 4. Successful consumption of 1 unit
            ApiResponse consumeResp = tryConsume(base, 1L, config.runId() + "-consume");
            if (!consumeResp.isOk()) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Consumption of 1 unit was refused (HTTP " + consumeResp.status() +
                        ") — expected success");
            }

            // 5. Balance must decrement by exactly 1
            long balanceAfterConsume = fetchBalance(base);
            if (balanceAfterConsume != initialBalance - 1) {
                return GateResult.fail(getClass().getSimpleName(), dur(start),
                        "Balance after consuming 1 unit is " + balanceAfterConsume +
                        "; expected " + (initialBalance - 1) + " — decrement invariant violated");
            }

            return GateResult.pass(getClass().getSimpleName(), dur(start),
                    "Over-consumption refused, balance unchanged after refusal, " +
                    "successful consumption decremented by 1 (balance now " + balanceAfterConsume + ")");

        } catch (IOException e) {
            return GateResult.setupError(getClass().getSimpleName(),
                    "Transport error in inventory gate: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private long fetchBalance(String base) throws IOException, InterruptedException {
        ApiResponse resp = http.get(base + "/api/v1/inventory/stock?partId=" + VALIDATION_PART_ID);
        if (!resp.isOk()) return -1L;
        JsonNode body = resp.json();
        // Try common field names for the balance
        JsonNode qty = body.path("quantity");
        if (qty.isMissingNode()) qty = body.path("balance");
        if (qty.isMissingNode()) qty = body.path("availableQuantity");
        if (qty.isMissingNode()) {
            // Try array response
            JsonNode arr = body.isArray() ? body : body.path("data");
            if (arr.isArray() && !arr.isEmpty()) {
                for (JsonNode item : arr) {
                    if (VALIDATION_PART_ID.equals(item.path("partId").asText(""))) {
                        return item.path("quantity").asLong(
                               item.path("balance").asLong(
                               item.path("availableQuantity").asLong(-1)));
                    }
                }
            }
            return -1L;
        }
        return qty.asLong(-1);
    }

    private ApiResponse tryConsume(String base, long qty, String reference)
            throws IOException, InterruptedException {
        String body = String.format(
                "{\"partId\":\"%s\",\"quantity\":%d,\"reference\":\"%s\"}",
                VALIDATION_PART_ID, qty, reference);
        return http.post(base + "/api/v1/inventory/consume", body);
    }

    private static Duration dur(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
