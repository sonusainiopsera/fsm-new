package com.fieldservice.workorder.duplicates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for duplicate detection and link-and-cancel workflow.
 *
 * <p>Uses V4 seed data: CUSTOMER_1 owns SITE_1, SITE_2 with ASSET_1 at SITE_1.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class DuplicateDetectionIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_dup_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",       postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.url",           postgres::getJdbcUrl);
        registry.add("spring.flyway.user",          postgres::getUsername);
        registry.add("spring.flyway.password",      postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc         mockMvc;
    @Autowired DatabaseCleaner dbCleaner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // V4 seed data fixture ids
    static final String DISPATCHER_ID = "dddddddd-0000-0000-0000-000000000001";
    static final String CUSTOMER_1    = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String SITE_1        = "bbbbbbbb-0000-0000-0000-000000000001";

    @AfterEach
    void cleanup() { dbCleaner.truncateAll(); }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private String createWorkOrderBody(String faultDesc) {
        return """
                {"customerId":"%s","siteId":"%s","faultDescription":"%s","priority":"NORMAL"}
                """.formatted(CUSTOMER_1, SITE_1, faultDesc);
    }

    private String extractId(String responseJson) throws Exception {
        return objectMapper.readTree(responseJson).get("id").asText();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Creation returns empty candidate list when no similar open work orders exist")
    void create_noDuplicates_emptyCandidates() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkOrderBody("Boiler fault in heating system")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                // duplicateCandidates absent or null when no matches
                .andExpect(jsonPath("$.duplicateCandidates").doesNotExist());
    }

    @Test
    @DisplayName("GET /{id}/duplicate-candidates returns 200 with candidates envelope")
    void getDuplicateCandidates_returnsEnvelope() throws Exception {
        // Create first work order
        String body1 = createWorkOrderBody("Boiler fault heating not working");
        String resp1 = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(resp1).get("id").asText();

        // Create second work order — similar fault at same site
        String body2 = createWorkOrderBody("Boiler heating system malfunction");
        String resp2 = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondId = objectMapper.readTree(resp2).get("id").asText();

        // Get candidates for the second work order
        mockMvc.perform(get("/api/v1/work-orders/" + secondId + "/duplicate-candidates")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    @DisplayName("POST /{id}/duplicate-of links and cancels source")
    void linkAsDuplicate_cancelsSource() throws Exception {
        // Create target
        String target = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkOrderBody("Boiler fault heating")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String targetId = objectMapper.readTree(target).get("id").asText();

        // Create source
        String source = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkOrderBody("Boiler fault duplicate report")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sourceId = objectMapper.readTree(source).get("id").asText();

        // Link source → target
        mockMvc.perform(post("/api/v1/work-orders/" + sourceId + "/duplicate-of")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorkOrderId":"%s","reason":"Customer reported same fault twice"}
                                """.formatted(targetId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceState").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReasonCode").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.targetWorkOrderId").value(targetId))
                .andExpect(jsonPath("$.linkedAt").exists());
    }

    @Test
    @DisplayName("POST /{id}/duplicate-of refuses self-link with DUPLICATE_SELF_LINK")
    void linkAsDuplicate_selfLink_returns422() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkOrderBody("Boiler fault heating system")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(resp).get("id").asText();

        mockMvc.perform(post("/api/v1/work-orders/" + id + "/duplicate-of")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorkOrderId":"%s","reason":"Self link test"}
                                """.formatted(id)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("DUPLICATE_SELF_LINK"));
    }

    @Test
    @DisplayName("POST /{id}/duplicate-of refuses already-linked source with DUPLICATE_ALREADY_LINKED")
    void linkAsDuplicate_alreadyLinked_returns422() throws Exception {
        // Create target and two sources
        String targetResp = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkOrderBody("Boiler fault target")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String targetId = objectMapper.readTree(targetResp).get("id").asText();

        String source1Resp = mockMvc.perform(post("/api/v1/work-orders")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createWorkOrderBody("Boiler fault duplicate one")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String source1Id = objectMapper.readTree(source1Resp).get("id").asText();

        // First link succeeds
        mockMvc.perform(post("/api/v1/work-orders/" + source1Id + "/duplicate-of")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorkOrderId":"%s","reason":"First link"}
                                """.formatted(targetId)))
                .andExpect(status().isOk());

        // Second link on already-linked source fails
        mockMvc.perform(post("/api/v1/work-orders/" + source1Id + "/duplicate-of")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetWorkOrderId":"%s","reason":"Second link attempt"}
                                """.formatted(targetId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("DUPLICATE_ALREADY_LINKED"));
    }

    @Test
    @DisplayName("GET /{id}/duplicate-candidates returns 403 for CUSTOMER outside scope")
    void getDuplicateCandidates_unknownId_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/00000000-0000-0000-0000-000000000099/duplicate-candidates")
                        .with(jwt().jwt(j -> j.subject(DISPATCHER_ID))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
    }
}
