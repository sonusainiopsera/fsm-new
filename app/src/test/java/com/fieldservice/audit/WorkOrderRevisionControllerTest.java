package com.fieldservice.audit;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for the work order revisions endpoint.
 *
 * <p>Uses the pre-seeded V101 fixture (WO_MULTI has 3 revisions) to verify:
 * <ul>
 *   <li>Paginated response structure</li>
 *   <li>Descending revision order</li>
 *   <li>Role-based access denial for TECHNICIAN and CUSTOMER</li>
 *   <li>Default page size respected</li>
 * </ul>
 */
class WorkOrderRevisionControllerTest extends AbstractIntegrationTest {

    private static final String WO_MULTI = "40000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;

    @Test
    void dispatcherCanListRevisions() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevisions").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.revisions").isArray())
                .andExpect(jsonPath("$.revisions.length()").value(3));
    }

    @Test
    void revisionsOrderedNewestFirst() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                // First entry should be highest revision number (9003 = DEL)
                .andExpect(jsonPath("$.revisions[0].revisionNumber").value(9003))
                .andExpect(jsonPath("$.revisions[0].revisionType").value("DEL"))
                // Last entry should be the earliest (9001 = ADD)
                .andExpect(jsonPath("$.revisions[2].revisionNumber").value(9001))
                .andExpect(jsonPath("$.revisions[2].revisionType").value("ADD"));
    }

    @Test
    void actorAttributionReturnedForEachRevision() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].actorUserId")
                        .value("aaaaaaaa-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.revisions[0].actorRole").value("DISPATCHER"));
    }

    @Test
    void paginationLimitsResultCount() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI)
                        .param("page", "0")
                        .param("size", "2")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevisions").value(3))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.revisions.length()").value(2));
    }

    @Test
    void technicianReceivesForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerReceivesForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedReceivesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_MULTI))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workOrderWithNoRevisionHistoryReturnsEmptyList() throws Exception {
        String unknownId = "99999999-0000-0000-0000-000000000000";
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", unknownId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevisions").value(0))
                .andExpect(jsonPath("$.revisions").isEmpty());
    }
}
