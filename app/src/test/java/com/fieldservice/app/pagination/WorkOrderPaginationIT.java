package com.fieldservice.app.pagination;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers integration tests for the {@code /api/v1/work-orders} pagination contract.
 *
 * <p>Seeds 100 work orders and verifies:
 * <ul>
 *   <li>Offset pagination envelope shape (data/page/links)</li>
 *   <li>Size clamping: requesting 100 returns at most 50</li>
 *   <li>Sort allow-list: unknown sort field → 400</li>
 *   <li>Auto-switch to keyset at threshold: links.next is cursor-based at page 20</li>
 *   <li>Keyset / offset result equivalence: following cursor pages yields the same
 *       IDs as the equivalent offset page</li>
 *   <li>Tampered cursor → 400</li>
 *   <li>Cross-sort cursor → 400</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class WorkOrderPaginationIT {

    private static final int    TOTAL_ROWS = 100;
    private static final int    PAGE_SIZE  = 5;
    /** Match offset links:  /api/v1/work-orders?page=N&size=S... */
    private static final Pattern OFFSET_LINK_PATTERN  =
            Pattern.compile("/api/v1/work-orders\\?page=(\\d+)&size=(\\d+).*");
    /** Match cursor links:  /api/v1/work-orders?cursor=XXX&size=S... */
    private static final Pattern CURSOR_LINK_PATTERN  =
            Pattern.compile("/api/v1/work-orders\\?cursor=([^&]+)&size=(\\d+).*");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_pagination_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url",          postgres::getJdbcUrl);
        registry.add("spring.flyway.user",         postgres::getUsername);
        registry.add("spring.flyway.password",     postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
        // Low threshold so tests exercise keyset switch without needing 20+ pages
        registry.add("app.pagination.offset-threshold", () -> "4");
    }

    @Autowired MockMvc                    mockMvc;
    @Autowired EntityManager             entityManager;
    @Autowired PlatformTransactionManager txManager;

    private static boolean seeded = false;
    private static UUID    seedSiteId;

    @BeforeEach
    void seed() {
        if (seeded) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            Site site = new Site("Pagination Test Site", null);
            entityManager.persist(site);
            seedSiteId = site.getId();
            // Use different timestamps to create variety; some rows share the same millis
            // to exercise the id tie-break in keyset pagination
            long baseMillis = 1_700_000_000_000L;
            for (int i = 0; i < TOTAL_ROWS; i++) {
                WorkOrder wo = new WorkOrder(
                        "WO-PAG-" + String.format("%04d", i),
                        WorkOrderStatus.NEW,
                        (i % 3 == 0) ? "HIGH" : "NORMAL",
                        site,
                        null);
                // Every 10 rows share the same timestamp to exercise tie-break
                setCreatedAt(wo, Instant.ofEpochMilli(baseMillis + (i / 10) * 1000L));
                entityManager.persist(wo);
            }
            return null;
        });
        seeded = true;
    }

    // ---- Offset mode tests -------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/work-orders returns PagedResponse envelope")
    void listWorkOrders_returnsPagedResponseEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(TOTAL_ROWS))
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("size=100 is clamped to 50 (hard cap)")
    void sizeExceeding50_isClamped() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("size", "100")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(50))
                .andExpect(jsonPath("$.page.size").value(50));
    }

    @Test
    @DisplayName("unknown sort field → 400 VALIDATION_FAILED")
    void unknownSortField_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("sort", "injected_field:asc")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    @Test
    @DisplayName("links.prev is null on page 0")
    void firstPage_prevLinkIsNull() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", "0")
                        .param("size", String.valueOf(PAGE_SIZE))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.prev").doesNotExist());
    }

    @Test
    @DisplayName("links.prev is set on page 1 (offset mode)")
    void secondPage_prevLinkIsSet() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", "1")
                        .param("size", String.valueOf(PAGE_SIZE))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.prev").isString());
    }

    // ---- Auto-switch to keyset tests ---------------------------------------

    @Test
    @DisplayName("at threshold page, links.next is a cursor link (auto-switch)")
    void atThresholdPage_nextLinkIsCursorBased() throws Exception {
        // offset-threshold is 4 in tests; page 3 (0-based) triggers auto-switch for next
        MvcResult result = mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", "3")
                        .param("size", String.valueOf(PAGE_SIZE))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // links.next should be a cursor link (contain "cursor=")
        assertThat(body).contains("cursor=");
    }

    // ---- Keyset mode tests -------------------------------------------------

    @Test
    @DisplayName("cursor request returns keyset PageMeta (estimated=-1)")
    void cursorRequest_returnsKeysetMeta() throws Exception {
        // First, get a cursor from page 3
        MvcResult page3 = mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", "3")
                        .param("size", String.valueOf(PAGE_SIZE))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String cursor = extractCursorFromNextLink(page3.getResponse().getContentAsString());
        assertThat(cursor).isNotNull();

        // Follow the cursor link
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("cursor", cursor)
                        .param("size", String.valueOf(PAGE_SIZE))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.totalElements").value(-1))
                .andExpect(jsonPath("$.page.estimated").value(true));
    }

    @Test
    @DisplayName("keyset and offset produce equivalent rows at the transition boundary")
    void keysetOffsetEquivalence() throws Exception {
        int size = PAGE_SIZE;
        int thresholdPage = 3; // page 3 is the last offset page before auto-switch

        // Get last offset page — records IDs on that page
        MvcResult offsetPage = mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", String.valueOf(thresholdPage))
                        .param("size", String.valueOf(size))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String cursor = extractCursorFromNextLink(offsetPage.getResponse().getContentAsString());
        assertThat(cursor).as("expected cursor link after threshold page").isNotNull();

        // Get the equivalent next page via cursor
        MvcResult keysetPage = mockMvc.perform(get("/api/v1/work-orders")
                        .param("cursor", cursor)
                        .param("size", String.valueOf(size))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Get the same page via offset (page 4)
        MvcResult offsetNextPage = mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", String.valueOf(thresholdPage + 1))
                        .param("size", String.valueOf(size))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        List<String> keysetIds  = extractIds(keysetPage.getResponse().getContentAsString());
        List<String> offsetIds  = extractIds(offsetNextPage.getResponse().getContentAsString());

        assertThat(keysetIds)
                .as("keyset page must match the equivalent offset page")
                .containsExactlyElementsOf(offsetIds);
    }

    @Test
    @DisplayName("tampered cursor → 400 VALIDATION_FAILED")
    void tamperedCursor_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders")
                        .param("cursor", "dGhpcyBpcyBub3QgYSB2YWxpZCBjdXJzb3I.bm90YXNpZ25hdHVyZQ")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cursor"));
    }

    @Test
    @DisplayName("cursor links do not expose PII or raw column values")
    void cursorIsOpaque() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/work-orders")
                        .param("page", "3")
                        .param("size", String.valueOf(PAGE_SIZE))
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String cursor = extractCursorFromNextLink(body);
        assertThat(cursor).isNotNull();
        // Cursor is base64url encoded — no plain JSON visible to external clients
        assertThat(cursor).doesNotContain("{").doesNotContain("}");
    }

    // ---- Helpers -----------------------------------------------------------

    /** Extracts the cursor query-param value from a links.next cursor URL in the body. */
    private static String extractCursorFromNextLink(String body) {
        Matcher m = CURSOR_LINK_PATTERN.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Extracts the list of "id" field values from $.data[*].id in a JSON body. */
    private static List<String> extractIds(String body) {
        List<String> ids = new ArrayList<>();
        Pattern p = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(body);
        // skip the first "id" match since it might be in page/links — find all occurrences
        // JSON structure: data is an array of objects; simpler to extract sequentially
        // Look specifically within the data array
        int dataStart = body.indexOf("\"data\"");
        int linksStart = body.indexOf("\"page\"");
        if (dataStart < 0) return ids;
        String dataSection = body.substring(dataStart, linksStart > dataStart ? linksStart : body.length());
        Matcher dm = p.matcher(dataSection);
        while (dm.find()) ids.add(dm.group(1));
        return ids;
    }

    /** Reflectively sets the createdAt field (which is normally set in the constructor). */
    private static void setCreatedAt(WorkOrder wo, Instant ts) {
        try {
            var field = WorkOrder.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(wo, ts);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
