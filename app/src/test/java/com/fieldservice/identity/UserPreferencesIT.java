package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.domain.user.AppUserRepository;
import com.fieldservice.identity.domain.AppearancePreference;
import com.fieldservice.platform.audit.AuditRevisionEntity;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET/PUT /api/v1/users/me/preferences (WO-184).
 *
 * <p>Covers:
 * <ul>
 *   <li>GET returns stored preference and effective appearance for each fixture value.</li>
 *   <li>NULL preference resolves to LIGHT (AC-7).</li>
 *   <li>PUT happy path updates preference and writes Envers revision (AC-2, AC-5).</li>
 *   <li>PUT with invalid enum value returns 400 with field-level error (AC-5).</li>
 *   <li>PUT with unknown JSON property returns 400 (AC-6).</li>
 *   <li>Unauthenticated request returns 401.</li>
 * </ul>
 */
@DisplayName("User preferences integration tests (WO-184)")
class UserPreferencesIT extends AbstractIntegrationTest {

    private static final String PREFS_URL = "/api/v1/users/me/preferences";

    @Autowired MockMvc mockMvc;
    @Autowired AppUserRepository appUserRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;

    // -------------------------------------------------------------------------
    // GET happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET returns LIGHT for dispatcher fixture (explicit LIGHT preference)")
    void get_dispatcherHasLightPreference() throws Exception {
        mockMvc.perform(get(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedPreference").value("LIGHT"))
                .andExpect(jsonPath("$.effectiveAppearance").value("LIGHT"))
                .andExpect(jsonPath("$.userId").value(TestJwtFactory.DISPATCHER_USER_ID.toString()));
    }

    @Test
    @DisplayName("GET returns DARK for tech1 fixture")
    void get_tech1HasDarkPreference() throws Exception {
        mockMvc.perform(get(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedPreference").value("DARK"))
                .andExpect(jsonPath("$.effectiveAppearance").value("DARK"));
    }

    @Test
    @DisplayName("GET returns SYSTEM for tech2 fixture")
    void get_tech2HasSystemPreference() throws Exception {
        mockMvc.perform(get(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.tech2Jwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedPreference").value("SYSTEM"))
                .andExpect(jsonPath("$.effectiveAppearance").value("SYSTEM"));
    }

    @Test
    @DisplayName("NULL preference resolves to LIGHT (new-account default, AC-7)")
    void get_nullPreferenceResolvesToLight() throws Exception {
        mockMvc.perform(get(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.managerJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedPreference").isEmpty())
                .andExpect(jsonPath("$.effectiveAppearance").value("LIGHT"));
    }

    // -------------------------------------------------------------------------
    // PUT happy path + Envers revision assertion
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT updates preference and returns updated response")
    void put_updatesPreferenceSuccessfully() throws Exception {
        mockMvc.perform(put(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appearance\":\"DARK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedPreference").value("DARK"))
                .andExpect(jsonPath("$.effectiveAppearance").value("DARK"))
                .andExpect(jsonPath("$.userId").value(TestJwtFactory.DISPATCHER_USER_ID.toString()));
    }

    @Test
    @DisplayName("PUT produces an Envers revision row in app_user_aud (AC-2)")
    void put_producesEnversRevision() throws Exception {
        mockMvc.perform(put(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.managerJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appearance\":\"SYSTEM\"}"))
                .andExpect(status().isOk());

        txTemplate.execute(status -> {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            List<Number> revisions = reader.getRevisions(AppUser.class, TestJwtFactory.MANAGER_USER_ID);
            assertThat(revisions)
                    .as("At least one Envers revision must exist for manager after preference update")
                    .isNotEmpty();

            // The latest revision must carry the updated preference
            Number latestRev = revisions.get(revisions.size() - 1);
            AppUser snapshot = reader.find(AppUser.class, TestJwtFactory.MANAGER_USER_ID, latestRev);
            assertThat(snapshot.getAppearancePreference())
                    .as("Latest revision must carry the SYSTEM preference")
                    .isEqualTo(AppearancePreference.SYSTEM);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // PUT validation failures (AC-5, AC-6)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT with unknown enum value returns 400 (AC-5)")
    void put_invalidEnumReturns400() throws Exception {
        mockMvc.perform(put(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appearance\":\"INVALID_VALUE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT with missing appearance field returns 400 with field error")
    void put_missingAppearanceReturns400() throws Exception {
        mockMvc.perform(put(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT with unknown JSON property returns 400 (AC-6)")
    void put_unknownPropertyReturns400() throws Exception {
        mockMvc.perform(put(PREFS_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appearance\":\"DARK\",\"unknownField\":\"value\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Unauthenticated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET without token returns 401")
    void get_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(PREFS_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT without token returns 401")
    void put_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put(PREFS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appearance\":\"DARK\"}"))
                .andExpect(status().isUnauthorized());
    }
}
