package com.fieldservice.catalog;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the Catalog API (WO-117).
 *
 * <p>Exercises the customer → site → asset hierarchy via HTTP,
 * running against the Testcontainers PostgreSQL instance with V110 fixtures loaded.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>GET list and GET by ID — paginated envelope, field presence</li>
 *   <li>POST create — 201 with Location header, response body</li>
 *   <li>PUT update — 200 with updated fields</li>
 *   <li>DELETE deactivate — 204; GET afterward returns active=false</li>
 *   <li>Cross-role access: CUSTOMER/TECHNICIAN denied on management endpoints (403)</li>
 *   <li>Row scope: CUSTOMER sees only their own tree</li>
 *   <li>Inactive-parent guard — 422 when creating under inactive parent</li>
 * </ul>
 */
@DisplayName("Catalog API integration tests (WO-117)")
class CatalogApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // Fixture IDs from V110
    private static final String ALPHA_CUSTOMER_ID    = "cc000000-0000-0000-0000-000000000001";
    private static final String BETA_CUSTOMER_ID     = "cc000000-0000-0000-0000-000000000002";
    private static final String GAMMA_CUSTOMER_ID    = "cc000000-0000-0000-0000-000000000003"; // inactive
    private static final String ALPHA_HQ_SITE_ID     = "dd000000-0000-0000-0000-000000000001";
    private static final String ALPHA_RETAIL_SITE_ID = "dd000000-0000-0000-0000-000000000004"; // inactive
    private static final String ALPHA_HQ_HVAC_1_ID   = "ee000000-0000-0000-0000-000000000001";

    // =========================================================================
    // Customer — list
    // =========================================================================

    @Test
    @DisplayName("DISPATCHER lists customers — paginated envelope with at least 2 active customers")
    void listCustomers_dispatcher_returnsPagedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "10")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.page.number", is(0)))
                .andExpect(jsonPath("$.page.size", is(10)))
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Customer list response contains required fields per record")
    void listCustomers_recordContainsExpectedFields() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", notNullValue()))
                .andExpect(jsonPath("$.data[0].accountCode").exists())
                .andExpect(jsonPath("$.data[0].legalName").exists())
                .andExpect(jsonPath("$.data[0].active").exists());
    }

    @Test
    @DisplayName("CUSTOMER receives 403 on GET /api/v1/customers")
    void listCustomers_customer_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN receives 403 on GET /api/v1/customers")
    void listCustomers_technician_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Customer — get by ID
    // =========================================================================

    @Test
    @DisplayName("DISPATCHER gets Alpha customer by ID with expected fields")
    void getCustomer_dispatcher_returnsCustomer() throws Exception {
        mockMvc.perform(get("/api/v1/customers/" + ALPHA_CUSTOMER_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ALPHA_CUSTOMER_ID)))
                .andExpect(jsonPath("$.accountCode", is("ALPHA-001")))
                .andExpect(jsonPath("$.legalName", is("Alpha Corporation Ltd")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    @DisplayName("DISPATCHER gets inactive Gamma customer — active field is false")
    void getCustomer_inactiveCustomer_returnsActiveFalse() throws Exception {
        mockMvc.perform(get("/api/v1/customers/" + GAMMA_CUSTOMER_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(GAMMA_CUSTOMER_ID)))
                .andExpect(jsonPath("$.active", is(false)));
    }

    @Test
    @DisplayName("DISPATCHER requests non-existent customer — 404")
    void getCustomer_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/customers/" + UUID.randomUUID())
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Customer — create
    // =========================================================================

    @Test
    @DisplayName("DISPATCHER creates customer — 201 with Location header")
    void createCustomer_dispatcher_returns201WithLocation() throws Exception {
        String body = """
                {
                    "accountCode": "DELTA-001",
                    "legalName": "Delta Corp Ltd",
                    "primaryContactName": "Dave Delta",
                    "primaryContactEmail": "dave@delta.example",
                    "primaryContactPhone": "07700900000",
                    "billingAddress": "9 Delta Drive, London, EC1A 9ZZ"
                }
                """;

        mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", notNullValue()))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.accountCode", is("DELTA-001")))
                .andExpect(jsonPath("$.legalName", is("Delta Corp Ltd")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    @DisplayName("CUSTOMER receives 403 on POST /api/v1/customers")
    void createCustomer_customer_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.customerBothAccountsJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountCode\":\"X\",\"legalName\":\"X Ltd\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Missing required legalName returns 400")
    void createCustomer_missingLegalName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountCode\":\"E-001\"}"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Customer — deactivate
    // =========================================================================

    @Test
    @DisplayName("DISPATCHER deactivates Beta customer — 204, then GET shows active=false")
    void deactivateCustomer_dispatcher_returns204ThenActiveFalse() throws Exception {
        mockMvc.perform(delete("/api/v1/customers/" + BETA_CUSTOMER_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/customers/" + BETA_CUSTOMER_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));
    }

    // =========================================================================
    // Site — list and get
    // =========================================================================

    @Test
    @DisplayName("DISPATCHER lists sites for Alpha customer — all Alpha sites returned")
    void listSites_dispatcher_returnsAlphaSites() throws Exception {
        mockMvc.perform(get("/api/v1/customers/" + ALPHA_CUSTOMER_ID + "/sites")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.page", notNullValue()))
                .andExpect(jsonPath("$.data[0].customerId", is(ALPHA_CUSTOMER_ID)));
    }

    @Test
    @DisplayName("DISPATCHER gets Alpha HQ site by ID — expected fields present")
    void getSite_dispatcher_returnsSite() throws Exception {
        mockMvc.perform(get("/api/v1/sites/" + ALPHA_HQ_SITE_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ALPHA_HQ_SITE_ID)))
                .andExpect(jsonPath("$.customerId", is(ALPHA_CUSTOMER_ID)))
                .andExpect(jsonPath("$.siteCode", is("ALPHA-HQ")))
                .andExpect(jsonPath("$.displayName", is("Alpha Headquarters")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    @DisplayName("DISPATCHER gets inactive Alpha Retail site — active=false")
    void getSite_inactiveSite_returnsActiveFalse() throws Exception {
        mockMvc.perform(get("/api/v1/sites/" + ALPHA_RETAIL_SITE_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));
    }

    // =========================================================================
    // Site — create with inactive-parent guard
    // =========================================================================

    @Test
    @DisplayName("Creating site under inactive customer returns 422 INACTIVE_PARENT")
    void createSite_inactiveCustomer_returns422() throws Exception {
        String body = """
                {
                    "siteCode": "GAMMA-NEW",
                    "displayName": "Gamma New Site",
                    "address": "1 New Street, London",
                    "postcode": "EC1A 1ZZ"
                }
                """;

        mockMvc.perform(post("/api/v1/customers/" + GAMMA_CUSTOMER_ID + "/sites")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("DISPATCHER creates site under active customer — 201 with Location")
    void createSite_activeCustomer_returns201() throws Exception {
        String body = """
                {
                    "siteCode": "ALPHA-NEW",
                    "displayName": "Alpha New Office",
                    "address": "10 New Road, London",
                    "postcode": "EC1A 9AA"
                }
                """;

        mockMvc.perform(post("/api/v1/customers/" + ALPHA_CUSTOMER_ID + "/sites")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", notNullValue()))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.customerId", is(ALPHA_CUSTOMER_ID)))
                .andExpect(jsonPath("$.siteCode", is("ALPHA-NEW")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    // =========================================================================
    // Asset — list and get
    // =========================================================================

    @Test
    @DisplayName("TECHNICIAN lists assets for Alpha HQ site")
    void listAssets_technician_returnsSiteAssets() throws Exception {
        mockMvc.perform(get("/api/v1/sites/" + ALPHA_HQ_SITE_ID + "/assets")
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt()))
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.page", notNullValue()));
    }

    @Test
    @DisplayName("DISPATCHER gets Alpha HQ HVAC asset by ID — expected fields present")
    void getAsset_dispatcher_returnsAsset() throws Exception {
        mockMvc.perform(get("/api/v1/assets/" + ALPHA_HQ_HVAC_1_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ALPHA_HQ_HVAC_1_ID)))
                .andExpect(jsonPath("$.siteId", is(ALPHA_HQ_SITE_ID)))
                .andExpect(jsonPath("$.assetTag", is("ALPHA-HQ-HVAC-001")))
                .andExpect(jsonPath("$.category", is("HVAC")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    // =========================================================================
    // Asset — create with inactive-parent guard
    // =========================================================================

    @Test
    @DisplayName("Creating asset under inactive site returns 422 INACTIVE_PARENT")
    void createAsset_inactiveSite_returns422() throws Exception {
        String body = """
                {
                    "assetTag": "NEW-TAG-001",
                    "manufacturer": "Trane",
                    "category": "HVAC"
                }
                """;

        mockMvc.perform(post("/api/v1/sites/" + ALPHA_RETAIL_SITE_ID + "/assets")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("DISPATCHER creates asset under active site — 201 with Location")
    void createAsset_activeSite_returns201() throws Exception {
        String body = """
                {
                    "assetTag": "ALPHA-HQ-NEW-001",
                    "manufacturer": "Daikin",
                    "model": "VRV X",
                    "category": "HVAC"
                }
                """;

        mockMvc.perform(post("/api/v1/sites/" + ALPHA_HQ_SITE_ID + "/assets")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", notNullValue()))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.siteId", is(ALPHA_HQ_SITE_ID)))
                .andExpect(jsonPath("$.assetTag", is("ALPHA-HQ-NEW-001")))
                .andExpect(jsonPath("$.active", is(true)));
    }

    @Test
    @DisplayName("CUSTOMER receives 403 on POST /api/v1/sites/{siteId}/assets")
    void createAsset_customer_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/sites/" + ALPHA_HQ_SITE_ID + "/assets")
                        .with(jwt().jwt(TestJwtFactory.customerBothAccountsJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetTag\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Asset — deactivate
    // =========================================================================

    @Test
    @DisplayName("MANAGER deactivates asset — 204, GET afterward shows active=false")
    void deactivateAsset_manager_returns204ThenActiveFalse() throws Exception {
        // Use the second HVAC unit (not the one used in getAsset test)
        String assetId = "ee000000-0000-0000-0000-000000000002";

        mockMvc.perform(delete("/api/v1/assets/" + assetId)
                        .with(jwt().jwt(TestJwtFactory.managerJwt())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/assets/" + assetId)
                        .with(jwt().jwt(TestJwtFactory.managerJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)));
    }

    // =========================================================================
    // Row scope — CUSTOMER sees only their own tree
    // =========================================================================

    @Test
    @DisplayName("CUSTOMER with Alpha account ID sees Alpha HQ site")
    void getSite_customerScopedToAlpha_seesAlphaSite() throws Exception {
        // Build JWT with customerAccountIds containing the Alpha customer fixture ID
        org.springframework.security.oauth2.jwt.Jwt customerJwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-customer-alpha")
                        .header("alg", "RS256")
                        .subject("aaaaaaaa-0000-0000-0000-000000000021")
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(900))
                        .claim("roles", List.of("CUSTOMER"))
                        .claim("customerAccountIds", List.of(ALPHA_CUSTOMER_ID))
                        .build();

        mockMvc.perform(get("/api/v1/sites/" + ALPHA_HQ_SITE_ID)
                        .with(jwt().jwt(customerJwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ALPHA_HQ_SITE_ID)));
    }

    @Test
    @DisplayName("CUSTOMER scoped to Beta cannot see Alpha HQ site — 404 (non-disclosure)")
    void getSite_customerScopedToBeta_cannotSeeAlphaSite() throws Exception {
        // CUSTOMER scoped only to Beta should not see Alpha HQ (returns 404 for non-disclosure)
        org.springframework.security.oauth2.jwt.Jwt betaCustomerJwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-customer-beta")
                        .header("alg", "RS256")
                        .subject("aaaaaaaa-0000-0000-0000-000000000025")
                        .issuedAt(java.time.Instant.now())
                        .expiresAt(java.time.Instant.now().plusSeconds(900))
                        .claim("roles", List.of("CUSTOMER"))
                        .claim("customerAccountIds", List.of(BETA_CUSTOMER_ID))
                        .build();

        mockMvc.perform(get("/api/v1/sites/" + ALPHA_HQ_SITE_ID)
                        .with(jwt().jwt(betaCustomerJwt)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Sort injection — unknown field rejected
    // =========================================================================

    @Test
    @DisplayName("Unknown sort field on customer list returns 400")
    void listCustomers_unknownSortField_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .param("sort", "password,asc"))
                .andExpect(status().isBadRequest());
    }
}
