package com.fieldservice.fixtures;

import com.fieldservice.domain.inventory.Part;
import com.fieldservice.domain.inventory.StockBalance;
import com.fieldservice.domain.inventory.StockLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InventoryFixtures unit tests")
class InventoryFixturesTest {

    private final UUID stubPartId     = UUID.fromString("aaaaaaaa-1111-0000-0000-000000000001");
    private final UUID stubLocationId = UUID.fromString("aaaaaaaa-2222-0000-0000-000000000001");

    @Test
    @DisplayName("activePart() produces an active part with non-blank fields")
    void activePart_defaults() {
        Part part = InventoryFixtures.activePart().build();
        assertThat(part.isActive()).isTrue();
        assertThat(part.getPartNumber()).isNotBlank();
        assertThat(part.getName()).isNotBlank();
        assertThat(part.getUnitOfMeasure()).isNotBlank();
        assertThat(part.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("inactivePart() produces an inactive part")
    void inactivePart_isInactive() {
        Part part = InventoryFixtures.inactivePart().build();
        assertThat(part.isActive()).isFalse();
    }

    @Test
    @DisplayName("warehouseLocation() has WAREHOUSE type and no technician link")
    void warehouseLocation_noTechnicianId() {
        StockLocation loc = InventoryFixtures.warehouseLocation().build();
        assertThat(loc.getLocationType()).isEqualTo("WAREHOUSE");
        assertThat(loc.getTechnicianId()).isNull();
        assertThat(loc.isActive()).isTrue();
    }

    @Test
    @DisplayName("vehicleLocation() has VEHICLE type and the supplied technician ID")
    void vehicleLocation_hasTechnicianId() {
        UUID techId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        StockLocation loc = InventoryFixtures.vehicleLocation(techId).build();
        assertThat(loc.getLocationType()).isEqualTo("VEHICLE");
        assertThat(loc.getTechnicianId()).isEqualTo(techId);
    }

    @Test
    @DisplayName("zeroBalance() produces a balance row with quantity_on_hand = 0")
    void zeroBalance_isZero() {
        StockBalance balance = InventoryFixtures.zeroBalance(stubPartId, stubLocationId);
        assertThat(balance.getQuantityOnHand()).isZero();
        assertThat(balance.getPartId()).isEqualTo(stubPartId);
        assertThat(balance.getLocationId()).isEqualTo(stubLocationId);
    }

    @Test
    @DisplayName("positiveBalance() produces the requested positive quantity")
    void positiveBalance_correctQuantity() {
        StockBalance balance = InventoryFixtures.positiveBalance(stubPartId, stubLocationId, 15);
        assertThat(balance.getQuantityOnHand()).isEqualTo(15);
    }

    @Test
    @DisplayName("positiveBalance(0) throws — use zeroBalance() for zero")
    void positiveBalance_zeroThrows() {
        assertThatThrownBy(() -> InventoryFixtures.positiveBalance(stubPartId, stubLocationId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zeroBalance");
    }

    @Test
    @DisplayName("positiveBalance with negative quantity throws — guarded by check")
    void positiveBalance_negativeThrows() {
        assertThatThrownBy(() -> InventoryFixtures.positiveBalance(stubPartId, stubLocationId, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zeroBalance and missing row are distinct: zeroBalance returns object, no row = null")
    void zeroBalance_distinctFromMissingRow() {
        // zeroBalance() ALWAYS returns an object (the "row exists" case)
        StockBalance row = InventoryFixtures.zeroBalance(stubPartId, stubLocationId);
        assertThat(row).isNotNull();
        assertThat(row.getQuantityOnHand()).isZero();
        // "No row" is represented by the caller simply not creating a StockBalance at all.
        // This test documents the contract — tests do not create a balance for "missing" case.
    }
}
