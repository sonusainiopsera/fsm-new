package com.fieldservice.inventory;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the inventory read endpoints.
 *
 * <p>Covers:
 * <ul>
 *   <li>GET /api/v1/inventory/parts — paginated response envelope, CUSTOMER 403.</li>
 *   <li>GET /api/v1/inventory/stock — paginated response envelope, CUSTOMER 403,
 *       TECHNICIAN scope restriction to own vehicle locations.</li>
 *   <li>Page size clamping at 50.</li>
 * </ul>
 *
 * <p>Relies on the inventory fixtures in V100__test_fixtures.sql (20 parts,
 * 3 warehouse locations, 5 vehicle locations, 15 stock balances).
 */
@DisplayName("Inventory API integration tests")
class InventoryApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // Fixed fixture IDs from V100
    private static final String TECH1_VAN_A = "60000000-0000-0000-0000-000000000011";
    private static final String TECH2_VAN_A = "60000000-0000-0000-0000-000000000014";

    // -------------------------------------------------------------------------
    // GET /api/v1/inventory/parts — dispatcher (permit-all)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DISPATCHER gets paginated parts list")
    void dispatcher_listParts_returnsPaginatedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "10")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.page.number", is(0)))
                .andExpect(jsonPath("$.page.size", is(10)))
                .andExpect(jsonPath("$.page.totalElements").value(20))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Page size is clamped to 50")
    void listParts_pageSizeClamped() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size", is(50)));
    }

    @Test
    @DisplayName("Parts list response contains expected fields per record")
    void listParts_recordContainsExpectedFields() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "1")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", notNullValue()))
                .andExpect(jsonPath("$.data[0].partNumber", notNullValue()))
                .andExpect(jsonPath("$.data[0].unitOfMeasure", notNullValue()))
                .andExpect(jsonPath("$.data[0].reorderPoint").exists())
                .andExpect(jsonPath("$.data[0].reorderQuantity").exists())
                .andExpect(jsonPath("$.data[0].active").exists());
    }

    // -------------------------------------------------------------------------
    // CUSTOMER denied on parts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CUSTOMER receives 403 on GET /api/v1/inventory/parts")
    void customer_listParts_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts")
                        .with(jwt().jwt(TestJwtFactory.customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/inventory/stock — dispatcher
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DISPATCHER gets paginated stock list")
    void dispatcher_listStock_returnsPaginatedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.page", notNullValue()))
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Stock list response contains expected fields per record")
    void listStock_recordContainsExpectedFields() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].partId", notNullValue()))
                .andExpect(jsonPath("$.data[0].stockLocationId", notNullValue()))
                .andExpect(jsonPath("$.data[0].quantityOnHand").exists())
                .andExpect(jsonPath("$.data[0].locationType", notNullValue()));
    }

    // -------------------------------------------------------------------------
    // CUSTOMER denied on stock
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CUSTOMER receives 403 on GET /api/v1/inventory/stock")
    void customer_listStock_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt().jwt(TestJwtFactory.customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // TECHNICIAN scope: sees only their own vehicle locations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TECHNICIAN 1 sees stock for their own vehicle locations only")
    void tech1_seesOnlyOwnVehicleStock() throws Exception {
        // Tech 1 has 3 vehicle locations; balances in fixtures for van A: 3 records
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt()))
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].locationType").value(
                        org.hamcrest.Matchers.everyItem(is("VEHICLE"))));
    }

    @Test
    @DisplayName("TECHNICIAN 1 cannot see stock for a location owned by Technician 2")
    void tech1_cannotSeeTech2Stock() throws Exception {
        // Filter by tech2's location — should return empty since tech1 cannot see it
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt()))
                        .param("locationId", TECH2_VAN_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // -------------------------------------------------------------------------
    // Filter parameters
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Stock endpoint filters by locationId for DISPATCHER")
    void dispatcher_listStock_filteredByLocation() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("locationId", TECH1_VAN_A)
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    // -------------------------------------------------------------------------
    // Unauthenticated access
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unauthenticated request to parts returns 401")
    void unauthenticated_listParts_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/parts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Unauthenticated request to stock returns 401")
    void unauthenticated_listStock_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/stock"))
                .andExpect(status().isUnauthorized());
    }
}
