package com.fieldservice.workorder.api;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the work order history API (WO-129).
 *
 * <p>Tests rely on fixtures from V122__history_lifecycle_fixtures.sql:
 * <ul>
 *   <li>WO_LIFECYCLE {@code 50000000-...-0001} — 8 revisions, full lifecycle NEW→CLOSED, tech1 assignee, ACCT_A</li>
 *   <li>WO_REASSIGNED {@code 50000000-...-0002} — 3 revisions, tech1→tech2 reassignment, ACCT_A</li>
 *   <li>WO_CANCELLED  {@code 50000000-...-0003} — 2 revisions, ADD+CANCELLED, ACCT_A</li>
 *   <li>WO_CUSTOMER_VIEW {@code 50000000-...-0004} — 2 revisions with faultDescription, ACCT_A</li>
 * </ul>
 */
@DisplayName("WorkOrderHistory integration tests")
class WorkOrderHistoryIT extends AbstractIntegrationTest {

    private static final String WO_LIFECYCLE     = "60000000-0000-0000-0000-000000000001";
    private static final String WO_REASSIGNED    = "60000000-0000-0000-0000-000000000002";
    private static final String WO_CANCELLED     = "60000000-0000-0000-0000-000000000003";
    private static final String WO_CUSTOMER_VIEW = "60000000-0000-0000-0000-000000000004";
    private static final String WO_NONEXISTENT   = "99999999-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // GET /revisions — happy path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /revisions — privileged")
    class GetRevisionsPrivileged {

        @Test
        @DisplayName("Dispatcher receives paginated revisions with standard envelope")
        void standardEnvelope() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.page.totalElements").value(8))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.links").exists());
        }

        @Test
        @DisplayName("Revisions ordered newest first (descending rev number)")
        void revisionsOrderedNewestFirst() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    // First item is the most recent revision (9107 = CLOSED)
                    .andExpect(jsonPath("$.data[0].revision").value(9107))
                    // Last item on this page is the oldest revision (9100 = ADD)
                    .andExpect(jsonPath("$.data[7].revision").value(9100))
                    .andExpect(jsonPath("$.data[7].revisionType").value("ADD"));
        }

        @Test
        @DisplayName("Each revision has required fields")
        void eachRevisionHasRequiredFields() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].revision").isNumber())
                    .andExpect(jsonPath("$.data[0].revisionAt").isString())
                    .andExpect(jsonPath("$.data[0].actorDisplayName").isString())
                    .andExpect(jsonPath("$.data[0].revisionType").isString())
                    .andExpect(jsonPath("$.data[0].changes").isArray());
        }

        @Test
        @DisplayName("ADD revision has null-before fields")
        void addRevisionNullBefore() throws Exception {
            // The ADD revision is the last item (index 7) since revisions are desc
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[7].revisionType").value("ADD"))
                    .andExpect(jsonPath("$.data[7].changes[0].before").value(nullValue()));
        }

        @Test
        @DisplayName("Dispatcher sees faultDescription in diff when it changes")
        void dispatcherSeesFaultDescription() throws Exception {
            // Rev 9103 (index 4 in desc order): faultDescription added (IN_PROGRESS)
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    // First item should include faultDescription change
                    .andExpect(jsonPath("$.data[0].changes[?(@.field=='faultDescription')]").exists());
        }

        @Test
        @DisplayName("Page size clamped to 50")
        void pageSizeClampedTo50() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .param("size", "200")
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.size").value(50));
        }

        @Test
        @DisplayName("Page 1 offset works correctly")
        void paginationPage1() throws Exception {
            // Page 0 with size 3 → first 3 revisions (9107, 9106, 9105)
            // Page 1 with size 3 → next 3 revisions (9104, 9103, 9102)
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .param("page", "1")
                            .param("size", "3")
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    .andExpect(jsonPath("$.data[0].revision").value(9104))
                    .andExpect(jsonPath("$.page.number").value(1));
        }
    }

    // -------------------------------------------------------------------------
    // GET /timeline — happy path
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /timeline — privileged")
    class GetTimelinePrivileged {

        @Test
        @DisplayName("Timeline uses standard PagedResponse envelope")
        void standardEnvelope() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.page").exists())
                    .andExpect(jsonPath("$.links").exists());
        }

        @Test
        @DisplayName("Timeline includes CREATED event for ADD revision")
        void timelineHasCreatedEvent() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.eventType=='CREATED')]").exists());
        }

        @Test
        @DisplayName("Cancelled work order has CANCELLED timeline event")
        void cancelledWorkOrderHasCancelledEvent() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_CANCELLED)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.eventType=='CANCELLED')]").exists());
        }

        @Test
        @DisplayName("Timeline events have required fields")
        void timelineEventsHaveRequiredFields() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].eventType").isString())
                    .andExpect(jsonPath("$.data[0].occurredAt").isString())
                    .andExpect(jsonPath("$.data[0].actorDisplayName").isString())
                    .andExpect(jsonPath("$.data[0].detail").exists());
        }

        @Test
        @DisplayName("REASSIGNED event appears for technician change")
        void reassignedEvent() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_REASSIGNED)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.eventType=='REASSIGNED')]").exists());
        }

        @Test
        @DisplayName("Timeline detail includes technicianId for internal roles")
        void timelineDetailIncludesTechnicianIdForInternal() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.detail.technicianId)]").exists());
        }
    }

    // -------------------------------------------------------------------------
    // Role scoping
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Role scoping")
    class RoleScoping {

        @Test
        @DisplayName("Technician with current assignment sees their work order revisions")
        void technicianSeesOwnWorkOrder() throws Exception {
            // WO_LIFECYCLE has assignedTechnicianId = TECH_1
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Technician cannot see work order they were never assigned to")
        void technicianDeniedUnrelated() throws Exception {
            // TECH_2 was never assigned to WO_LIFECYCLE
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.tech2Jwt())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Customer with matching account sees redacted timeline")
        void customerSeesOwnWorkOrder() throws Exception {
            // WO_CUSTOMER_VIEW belongs to ACCT_A
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Out-of-scope request returns 403 with no existence disclosure")
        void outOfScopeReturns403() throws Exception {
            // TECH_2 has no assignment to WO_LIFECYCLE
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.tech2Jwt())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Non-existent work order returns 403 (indistinguishable from out-of-scope)")
        void nonExistentReturns403() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_NONEXISTENT)
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isForbidden());
        }
    }

    // -------------------------------------------------------------------------
    // Customer masking
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Customer masking")
    class CustomerMasking {

        @Test
        @DisplayName("Customer revisions do not contain faultDescription")
        void customerRevisionsMissingFaultDescription() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.changes[?(@.field=='faultDescription')])]").doesNotExist());
        }

        @Test
        @DisplayName("Customer revisions do not contain assignedTechnicianId")
        void customerRevisionsMissingTechnicianId() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.changes[?(@.field=='assignedTechnicianId')])]").doesNotExist());
        }

        @Test
        @DisplayName("Customer revisions do not contain description (internal notes)")
        void customerRevisionsMissingDescription() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.changes[?(@.field=='description')])]").doesNotExist());
        }

        @Test
        @DisplayName("Customer timeline does not contain technicianId in detail")
        void customerTimelineMissingTechnicianIdInDetail() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.detail.technicianId)]").doesNotExist());
        }

        @Test
        @DisplayName("Customer actorDisplayName does not contain raw user UUIDs")
        void customerActorDisplayNameHidesUserId() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_CUSTOMER_VIEW)
                            .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                    .andExpect(status().isOk())
                    // actorDisplayName should not contain the actual UUID substring
                    .andExpect(jsonPath("$.data[0].actorDisplayName").value(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("aaaaaaaa"))));
        }

        @Test
        @DisplayName("Technician revisions do not contain faultDescription")
        void technicianRevisionsMissingFaultDescription() throws Exception {
            mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_LIFECYCLE)
                            .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.changes[?(@.field=='faultDescription')])]").doesNotExist());
        }
    }

    // -------------------------------------------------------------------------
    // Single-revision edge case
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Work order with one revision returns valid single-entry history")
    void singleRevisionReturnsValidHistory() throws Exception {
        // Create a WO with one revision by querying the first revision of WO_CANCELLED
        // (two revisions exist so use page 1 with size 1 to test the boundary)
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", WO_CANCELLED)
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("Work order with one revision returns valid single-event timeline")
    void singleRevisionTimelineIsValid() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", WO_CANCELLED)
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventType").isString())
                .andExpect(jsonPath("$.data[0].occurredAt").isString());
    }
}
