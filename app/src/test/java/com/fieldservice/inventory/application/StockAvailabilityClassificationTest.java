package com.fieldservice.inventory.application;

import com.fieldservice.inventory.api.AvailabilityStatus;
import com.fieldservice.inventory.api.CandidateAvailability;
import com.fieldservice.inventory.api.PartShortfall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for parts availability classification, shortfall computation,
 * verdict derivation, and cache key stability.
 * No Spring context — plain unit tests.
 */
class StockAvailabilityClassificationTest {

    private static final UUID PART_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PART_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID LOC_1  = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    private static final Map<UUID, String> PART_NUMBERS = Map.of(
            PART_A, "PN-001", PART_B, "PN-002");
    private static final Instant NOW = Instant.now();

    // ─── FULLY_STOCKED ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("FULLY_STOCKED when vehicle has all required parts")
    void fullyStocked_whenVehicleHasAll() {
        Map<UUID, Integer> required = Map.of(PART_A, 2);
        Map<UUID, Integer> vehicle  = Map.of(PART_A, 5);
        Map<UUID, Integer> warehouse = Map.of();

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, warehouse, PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.FULLY_STOCKED);
        assertThat(result.shortfalls()).isEmpty();
    }

    @Test
    @DisplayName("FULLY_STOCKED when required quantity is exactly met on vehicle")
    void fullyStocked_exactMatch() {
        Map<UUID, Integer> required = Map.of(PART_A, 3);
        Map<UUID, Integer> vehicle  = Map.of(PART_A, 3);

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, Map.of(), PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.FULLY_STOCKED);
    }

    // ─── COLLECTABLE ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("COLLECTABLE when vehicle is short but warehouse covers the shortfall")
    void collectable_whenWarehouseCoversMissingParts() {
        Map<UUID, Integer> required  = Map.of(PART_A, 5);
        Map<UUID, Integer> vehicle   = Map.of(PART_A, 2);
        Map<UUID, Integer> warehouse = Map.of(PART_A, 10);

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, warehouse, PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.COLLECTABLE);
        assertThat(result.shortfalls()).hasSize(1);
        PartShortfall shortfall = result.shortfalls().get(0);
        assertThat(shortfall.requested()).isEqualTo(5);
        assertThat(shortfall.available()).isEqualTo(12); // 2 vehicle + 10 warehouse
    }

    @Test
    @DisplayName("COLLECTABLE when vehicle has zero of a part but warehouse has enough")
    void collectable_zeroOnVehicle() {
        Map<UUID, Integer> required  = Map.of(PART_A, 1, PART_B, 1);
        Map<UUID, Integer> vehicle   = Map.of(); // nothing on vehicle
        Map<UUID, Integer> warehouse = Map.of(PART_A, 5, PART_B, 5);

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, warehouse, PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.COLLECTABLE);
    }

    // ─── PARTIALLY_STOCKED ──────────────────────────────────────────────────────

    @Test
    @DisplayName("PARTIALLY_STOCKED when some shortfalls are collectable but not all")
    void partiallyStocked_someCollectableSomeNot() {
        Map<UUID, Integer> required  = Map.of(PART_A, 2, PART_B, 3);
        Map<UUID, Integer> vehicle   = Map.of(PART_A, 1); // PART_A short by 1, PART_B missing
        Map<UUID, Integer> warehouse = Map.of(PART_A, 5); // PART_A collectable, PART_B not

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, warehouse, PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.PARTIALLY_STOCKED);
        assertThat(result.shortfalls()).hasSize(2);
    }

    // ─── UNAVAILABLE ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UNAVAILABLE when vehicle and warehouse both lack required parts")
    void unavailable_whenNeitherVehicleNorWarehouseHasParts() {
        Map<UUID, Integer> required  = Map.of(PART_A, 5);
        Map<UUID, Integer> vehicle   = Map.of();
        Map<UUID, Integer> warehouse = Map.of(PART_A, 2); // only 2, need 5

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, warehouse, PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
        PartShortfall shortfall = result.shortfalls().get(0);
        assertThat(shortfall.available()).isEqualTo(2);
        assertThat(shortfall.requested()).isEqualTo(5);
        assertThat(shortfall.partNumber()).isEqualTo("PN-001");
    }

    @Test
    @DisplayName("UNAVAILABLE when no stock anywhere")
    void unavailable_zeroEverywhere() {
        Map<UUID, Integer> required = Map.of(PART_A, 1);

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, Map.of(), Map.of(), PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
        assertThat(result.shortfalls().get(0).available()).isEqualTo(0);
    }

    // ─── Zero-quantity parts ignored ────────────────────────────────────────────

    @Test
    @DisplayName("Zero-quantity required lines are ignored — treated as FULLY_STOCKED")
    void zeroQuantityIgnored() {
        // Zero-quantity filter is in queryAvailability(); classify() receives pre-filtered map
        Map<UUID, Integer> required = Map.of();
        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, Map.of(), Map.of(), PART_NUMBERS, NOW);

        assertThat(result.status()).isEqualTo(AvailabilityStatus.FULLY_STOCKED);
    }

    // ─── Cache key stability ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Cache key is deterministic regardless of Map/Set iteration order")
    void cacheKey_isDeterministic() {
        Map<UUID, Integer> required = Map.of(PART_A, 2, PART_B, 1);
        java.util.Set<UUID> vehicles   = java.util.Set.of(LOC_1);
        java.util.Set<UUID> warehouses = java.util.Set.of(
                UUID.fromString("dddddddd-0000-0000-0000-000000000001"),
                UUID.fromString("eeeeeeee-0000-0000-0000-000000000002"));

        String key1 = StockAvailabilityServiceImpl.buildCacheKey(required, vehicles, warehouses);
        String key2 = StockAvailabilityServiceImpl.buildCacheKey(required, vehicles, warehouses);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).startsWith("parts-avail:");
    }

    @Test
    @DisplayName("Cache key differs when required quantities differ")
    void cacheKey_differsForDifferentQuantities() {
        java.util.Set<UUID> locs = java.util.Set.of(LOC_1);
        String key1 = StockAvailabilityServiceImpl.buildCacheKey(Map.of(PART_A, 1), locs, locs);
        String key2 = StockAvailabilityServiceImpl.buildCacheKey(Map.of(PART_A, 2), locs, locs);
        assertThat(key1).isNotEqualTo(key2);
    }

    // ─── Degraded fallback ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Shortfall available field reflects vehicle + warehouse total")
    void shortfall_availableIsVehiclePlusWarehouse() {
        Map<UUID, Integer> required  = Map.of(PART_A, 10);
        Map<UUID, Integer> vehicle   = Map.of(PART_A, 3);
        Map<UUID, Integer> warehouse = Map.of(PART_A, 4);

        CandidateAvailability result = StockAvailabilityServiceImpl.classify(
                LOC_1, required, vehicle, warehouse, PART_NUMBERS, NOW);

        assertThat(result.shortfalls()).hasSize(1);
        assertThat(result.shortfalls().get(0).available()).isEqualTo(7); // 3 + 4
        assertThat(result.status()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
    }
}
