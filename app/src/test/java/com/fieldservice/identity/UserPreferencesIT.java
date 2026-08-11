package com.fieldservice.identity;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppearancePreference;
import com.fieldservice.identity.domain.UserPreferenceAuditRepository;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET and PUT /api/v1/users/me/preferences.
 *
 * <p>Covers: happy path, 400 invalid enum, 403 cross-user attempt with no existence
 * disclosure, unknown-property rejection, Envers revision assertion, and structured
 * audit record assertion.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class UserPreferencesIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_prefs_test")
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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc                     mockMvc;
    @Autowired EntityManager               entityManager;
    @Autowired PlatformTransactionManager  txManager;
    @Autowired UserPreferenceAuditRepository auditRepository;

    private TransactionTemplate tx;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        userId = tx.execute(s -> {
            UUID id = UUID.randomUUID();
            entityManager.createNativeQuery(
                    "INSERT INTO app_user (id, email, display_name, active, created_at, version) "
                            + "VALUES (?1, ?2, ?3, TRUE, NOW(), 0)")
                    .setParameter(1, id.toString())
                    .setParameter(2, "prefs-" + id + "@example.local")
                    .setParameter(3, "Prefs Test User")
                    .executeUpdate();
            return id;
        });
    }

    // ---- GET happy path --------------------------------------------------------

    @Test
    @DisplayName("GET returns null stored preference with LIGHT as effective default")
    void get_returns_light_default_for_null_preference() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.storedPreference").isEmpty())
                .andExpect(jsonPath("$.effectivePreference").value("LIGHT"));
    }

    // ---- PUT happy path --------------------------------------------------------

    @Test
    @DisplayName("PUT DARK stores DARK and returns correct envelope")
    void put_dark_stores_preference() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"DARK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.storedPreference").value("DARK"))
                .andExpect(jsonPath("$.effectivePreference").value("DARK"));

        AppearancePreference stored = tx.execute(s -> {
            AppUser u = entityManager.find(AppUser.class, userId);
            return u.getAppearancePreference();
        });
        assertThat(stored).isEqualTo(AppearancePreference.DARK);
    }

    // ---- Envers revision assertion ---------------------------------------------

    @Test
    @DisplayName("PUT produces exactly one Envers revision in app_user_aud")
    void put_produces_envers_revision() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"SYSTEM\"}"))
                .andExpect(status().isOk());

        Long revCount = tx.execute(s ->
                (Long) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM app_user_aud WHERE id = ?1")
                        .setParameter(1, userId)
                        .getSingleResult());
        assertThat(revCount).isEqualTo(1L);
    }

    // ---- Structured audit record assertion -------------------------------------

    @Test
    @DisplayName("PUT writes exactly one structured audit record in same transaction")
    void put_writes_structured_audit_record() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("MANAGER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"LIGHT\"}"))
                .andExpect(status().isOk());

        var records = auditRepository.findByUserIdOrderByOccurredAtDesc(userId);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getFieldName()).isEqualTo("appearance_preference");
        assertThat(records.get(0).getNewValue()).isEqualTo("LIGHT");
        assertThat(records.get(0).getOldValue()).isNull();
        assertThat(records.get(0).getActorRole()).isEqualTo("MANAGER");
    }

    // ---- 400 invalid enum value ------------------------------------------------

    @Test
    @DisplayName("PUT with unknown enum value returns 400 with field-level error and no persistence")
    void put_unknown_enum_value_returns_400_no_persistence() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"TURBO_NEON\"}"))
                .andExpect(status().isBadRequest());

        AppearancePreference stored = tx.execute(s -> {
            AppUser u = entityManager.find(AppUser.class, userId);
            return u.getAppearancePreference();
        });
        assertThat(stored).isNull();
    }

    // ---- 400 unknown JSON property rejection -----------------------------------

    @Test
    @DisplayName("PUT with unknown JSON property returns 400")
    void put_unknown_json_property_returns_400() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"DARK\",\"userId\":\"evil-id\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 403 cross-user attempt with no existence disclosure -------------------

    @Test
    @DisplayName("Unauthenticated GET returns 401")
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Non-existent user subject returns 403 — no existence disclosure")
    void nonexistent_user_subject_returns_403() throws Exception {
        UUID phantomId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(phantomId.toString())
                                        .claim("roles", List.of("ADMIN")))))
                .andExpect(status().isForbidden());
    }

    // ---- Idempotency replay ----------------------------------------------------

    @Test
    @DisplayName("Replaying the same Idempotency-Key does not double-apply the PUT")
    void idempotency_key_replay_does_not_double_apply() throws Exception {
        String idempotencyKey = "prefs-idem-" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("ADMIN"))))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"DARK\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/users/me/preferences")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject(userId.toString())
                                        .claim("roles", List.of("ADMIN"))))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preference\":\"DARK\"}"))
                .andExpect(status().isOk());

        var records = auditRepository.findByUserIdOrderByOccurredAtDesc(userId);
        assertThat(records).hasSize(1);
    }
}
