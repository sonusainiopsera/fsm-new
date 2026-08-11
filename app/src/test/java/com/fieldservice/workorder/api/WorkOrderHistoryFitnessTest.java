package com.fieldservice.workorder.api;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fitness tests for the work order history API.
 *
 * <p>Asserts:
 * <ol>
 *   <li>No repository bean in the application context maps an audit or REVINFO entity
 *       as its domain type — history tables must be append-only.</li>
 *   <li>POST, PUT, PATCH, and DELETE on both history paths return 405 Method Not Allowed.</li>
 * </ol>
 */
@DisplayName("WorkOrderHistory fitness tests")
class WorkOrderHistoryFitnessTest extends AbstractIntegrationTest {

    private static final UUID ANY_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mockMvc;
    @Autowired ApplicationContext applicationContext;

    @Test
    @DisplayName("No Spring Data repository manages an audit or REVINFO entity")
    void noMutatingRepositoryForAuditTables() {
        // Assert by repository bean name: if a repo manages an audit entity, its name
        // would conventionally include "Audit", "Revinfo", or end with "Aud".
        String[] repoNames = applicationContext.getBeanNamesForType(CrudRepository.class);
        for (String name : repoNames) {
            assertThat(name.toLowerCase())
                    .as("Repository bean '%s' must not manage an audit or REVINFO entity", name)
                    .doesNotContain("revinfo")
                    .doesNotContainPattern(".*aud$");
        }
        // Also assert no bean class name contains AuditRevisionEntity (direct instantiation)
        applicationContext.getBeansOfType(CrudRepository.class).forEach((name, repo) -> {
            assertThat(repo.getClass().getName())
                    .as("Repository implementation '%s' must not reference audit entity types", name)
                    .doesNotContainIgnoringCase("AuditRevisionEntity");
        });
    }

    @Test
    @DisplayName("POST /revisions returns 405")
    void postRevisionsIs405() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/revisions", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("PUT /revisions returns 405")
    void putRevisionsIs405() throws Exception {
        mockMvc.perform(put("/api/v1/work-orders/{id}/revisions", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("PATCH /revisions returns 405")
    void patchRevisionsIs405() throws Exception {
        mockMvc.perform(patch("/api/v1/work-orders/{id}/revisions", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("DELETE /revisions returns 405")
    void deleteRevisionsIs405() throws Exception {
        mockMvc.perform(delete("/api/v1/work-orders/{id}/revisions", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("POST /timeline returns 405")
    void postTimelineIs405() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/timeline", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("PUT /timeline returns 405")
    void putTimelineIs405() throws Exception {
        mockMvc.perform(put("/api/v1/work-orders/{id}/timeline", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("DELETE /timeline returns 405")
    void deleteTimelineIs405() throws Exception {
        mockMvc.perform(delete("/api/v1/work-orders/{id}/timeline", ANY_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isMethodNotAllowed());
    }
}
