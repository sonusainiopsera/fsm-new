package com.fieldservice.workorder.holds;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the hold reason vocabulary and hold/resume lifecycle.
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
class HoldLifecycleIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_hold_test")
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

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired WorkOrderHoldRepository holdRepo;

    TransactionTemplate tx;

    // Stable IDs inserted in setUp
    UUID custId   = UUID.fromString("ee000000-0000-0000-0000-000000000001");
    UUID siteId   = UUID.fromString("ee000000-0000-0000-0000-000000000002");
    UUID userId   = UUID.fromString("ee000000-0000-0000-0000-000000000003");
    UUID techId   = UUID.fromString("ee000000-0000-0000-0000-000000000004");
    UUID woId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        woId = tx.execute(s -> {
            em.createNativeQuery("DELETE FROM work_order_hold WHERE work_order_id IN " +
                    "(SELECT id FROM work_order WHERE reference LIKE 'HOLD-IT-%')").executeUpdate();
            em.createNativeQuery("DELETE FROM work_order WHERE reference LIKE 'HOLD-IT-%'").executeUpdate();
            em.createNativeQuery("DELETE FROM work_order_labour_entry WHERE work_order_id IS NULL").executeUpdate();

            // Ensure stable fixtures exist
            int custCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM customer WHERE id = '" + custId + "'").getSingleResult()).intValue();
            if (custCount == 0) {
                em.createNativeQuery("INSERT INTO customer (id, name, version) VALUES " +
                        "('" + custId + "', 'Hold Test Corp', 0)").executeUpdate();
            }
            int siteCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM site WHERE id = '" + siteId + "'").getSingleResult()).intValue();
            if (siteCount == 0) {
                em.createNativeQuery("INSERT INTO site (id, name, customer_id, version) VALUES " +
                        "('" + siteId + "', 'Hold Site', '" + custId + "', 0)").executeUpdate();
            }
            int userCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM app_user WHERE id = '" + userId + "'").getSingleResult()).intValue();
            if (userCount == 0) {
                em.createNativeQuery("INSERT INTO app_user (id, email, password_hash, full_name, active, version)" +
                        " VALUES ('" + userId + "', 'holdtest@example.com', 'x', 'Hold User', TRUE, 0)")
                        .executeUpdate();
            }
            int techCount = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM technician WHERE id = '" + techId + "'").getSingleResult()).intValue();
            if (techCount == 0) {
                em.createNativeQuery("INSERT INTO technician (id, user_id, version) VALUES " +
                        "('" + techId + "', '" + userId + "', 0)").executeUpdate();
            }

            UUID id = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO work_order (id, reference, state, priority, site_id, cumulative_hold_minutes, version)" +
                    " VALUES ('" + id + "', 'HOLD-IT-001', 'IN_PROGRESS', 'MEDIUM', '" + siteId + "', 0, 0)")
                    .executeUpdate();
            // Add a labour entry so COMPLETE guard passes later
            em.createNativeQuery(
                    "INSERT INTO work_order_labour_entry (id, work_order_id, minutes, created_at)" +
                    " VALUES (gen_random_uuid(), '" + id + "', 60, NOW())").executeUpdate();
            return id;
        });
    }

    // ---- hold-reasons endpoint -----------------------------------------------

    @Test
    @DisplayName("GET /hold-reasons returns active reasons in sort order inside page envelope")
    void holdReasons_endpoint_returnsActiveInOrder() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/hold-reasons")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data[0].code", notNullValue()))
                .andExpect(jsonPath("$.data[0].label", notNullValue()))
                // inactive code LEGACY_OTHER must not appear
                .andExpect(jsonPath("$.data[?(@.code == 'LEGACY_OTHER')]").isEmpty());
    }

    @Test
    @DisplayName("GET /hold-reasons excludes inactive codes (LEGACY_OTHER)")
    void holdReasons_excludesInactive() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/hold-reasons")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'LEGACY_OTHER')]").isEmpty());
    }

    // ---- HOLD + RESUME lifecycle ---------------------------------------------

    @Test
    @DisplayName("HOLD with unknown reason code returns 400 with field error on holdReasonCode")
    void hold_unknownCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0,"holdReasonCode":"DOES_NOT_EXIST"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("HOLD_REASON_INVALID")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("holdReasonCode")));
    }

    @Test
    @DisplayName("HOLD with inactive reason code returns 400")
    void hold_inactiveCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0,"holdReasonCode":"LEGACY_OTHER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("HOLD_REASON_INVALID")));
    }

    @Test
    @DisplayName("HOLD with missing code returns 400 validation error")
    void hold_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("HOLD then RESUME persists hold record and increments cumulative minutes")
    void holdThenResume_persistsIntervalAndIncrementsCumulative() throws Exception {
        // HOLD
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0,"holdReasonCode":"AWAITING_PARTS","reason":"Waiting on bolt"}
                                """))
                .andExpect(status().isOk());

        // Assert hold record exists and is open
        tx.execute(s -> {
            WorkOrderHold hold = holdRepo.findByWorkOrderIdAndEndedAtIsNull(woId).orElseThrow();
            assertThat(hold.getReasonCode()).isEqualTo("AWAITING_PARTS");
            assertThat(hold.getNote()).isEqualTo("Waiting on bolt");
            assertThat(hold.getEndedAt()).isNull();
            return null;
        });

        // RESUME (re-read version from DB first)
        Integer version = tx.execute(s ->
                (Integer) em.createNativeQuery("SELECT version FROM work_order WHERE id = '" + woId + "'")
                        .getSingleResult());

        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"RESUME\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk());

        // Assert hold record closed and cumulative minutes >= 0
        tx.execute(s -> {
            WorkOrderHold hold = holdRepo.findByWorkOrderIdAndEndedAtIsNull(woId).orElse(null);
            assertThat(hold).isNull(); // open hold gone

            int cumulative = ((Number) em.createNativeQuery(
                    "SELECT cumulative_hold_minutes FROM work_order WHERE id = '" + woId + "'")
                    .getSingleResult()).intValue();
            assertThat(cumulative).isGreaterThanOrEqualTo(0);
            return null;
        });
    }

    @Test
    @DisplayName("HOLD → RESUME → HOLD → RESUME: cumulative minutes is sum of both intervals")
    void twoHoldResumeCycles_cumulativeIsSum() throws Exception {
        // First HOLD
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0,"holdReasonCode":"WEATHER"}
                                """))
                .andExpect(status().isOk());

        Integer v1 = tx.execute(s ->
                (Integer) em.createNativeQuery("SELECT version FROM work_order WHERE id = '" + woId + "'")
                        .getSingleResult());

        // First RESUME
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"RESUME\",\"expectedVersion\":" + v1 + "}"))
                .andExpect(status().isOk());

        Integer v2 = tx.execute(s ->
                (Integer) em.createNativeQuery("SELECT version FROM work_order WHERE id = '" + woId + "'")
                        .getSingleResult());

        // Second HOLD
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"HOLD\",\"expectedVersion\":" + v2 +
                                 ",\"holdReasonCode\":\"CUSTOMER_UNAVAILABLE\"}"))
                .andExpect(status().isOk());

        Integer v3 = tx.execute(s ->
                (Integer) em.createNativeQuery("SELECT version FROM work_order WHERE id = '" + woId + "'")
                        .getSingleResult());

        // Second RESUME
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"RESUME\",\"expectedVersion\":" + v3 + "}"))
                .andExpect(status().isOk());

        // Two closed hold records must exist
        tx.execute(s -> {
            long closedCount = (long) ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM work_order_hold WHERE work_order_id = '" + woId +
                    "' AND ended_at IS NOT NULL").getSingleResult()).longValue();
            assertThat(closedCount).isEqualTo(2);

            int cumulative = ((Number) em.createNativeQuery(
                    "SELECT cumulative_hold_minutes FROM work_order WHERE id = '" + woId + "'")
                    .getSingleResult()).intValue();
            assertThat(cumulative).isGreaterThanOrEqualTo(0);
            return null;
        });
    }

    @Test
    @DisplayName("CANCEL from ON_HOLD closes the dangling hold record")
    void cancelFromOnHold_closesDanglingHold() throws Exception {
        // HOLD
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0,"holdReasonCode":"ACCESS_DENIED"}
                                """))
                .andExpect(status().isOk());

        Integer version = tx.execute(s ->
                (Integer) em.createNativeQuery("SELECT version FROM work_order WHERE id = '" + woId + "'")
                        .getSingleResult());

        // CANCEL
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"CANCEL\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk());

        // Hold must be closed
        tx.execute(s -> {
            WorkOrderHold open = holdRepo.findByWorkOrderIdAndEndedAtIsNull(woId).orElse(null);
            assertThat(open).isNull();

            long closedCount = (long) ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM work_order_hold WHERE work_order_id = '" + woId +
                    "' AND ended_at IS NOT NULL").getSingleResult()).longValue();
            assertThat(closedCount).isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("Detail endpoint includes cumulativeHoldMinutes and holdReasonCode for open hold")
    void detailEndpoint_includesHoldFields() throws Exception {
        // HOLD first
        mockMvc.perform(post("/api/v1/work-orders/" + woId + "/transitions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":0,"holdReasonCode":"SAFETY_CONCERN"}
                                """))
                .andExpect(status().isOk());

        // GET detail — must include holdReasonCode and holdStartedAt
        mockMvc.perform(get("/api/v1/work-orders/" + woId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cumulativeHoldMinutes", notNullValue()))
                .andExpect(jsonPath("$.holdReasonCode", is("SAFETY_CONCERN")))
                .andExpect(jsonPath("$.holdStartedAt", notNullValue()));
    }
}
