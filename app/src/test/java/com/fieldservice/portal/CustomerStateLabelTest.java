package com.fieldservice.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.portal.i18n.CustomerStateLabels;
import com.fieldservice.portal.service.PortalStatusService;
import com.fieldservice.portal.web.dto.PortalStatusView;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests (no Spring context) for:
 * <ul>
 *   <li>{@link CustomerStateLabels} — coverage of every lifecycle state and hold reason</li>
 *   <li>{@link PortalStatusService#etag} — determinism and change detection</li>
 *   <li>{@link PortalStatusView} redaction — serialised JSON contains no forbidden fields</li>
 *   <li>Freshness degraded threshold</li>
 * </ul>
 */
class CustomerStateLabelTest {

    // -------------------------------------------------------------------------
    // AC-7 / AC-8: State label mapping for every lifecycle state
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-7: NEW state returns non-jargon customer label")
    void newState_returnsLabel() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.NEW, null);
        assertThat(label).isEqualTo(CustomerStateLabels.NEW);
        assertThat(label.getLabel()).isNotBlank().doesNotContain("NEW").doesNotContain("_");
        assertThat(label.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("AC-7: ASSIGNED state label contains no internal code")
    void assignedState_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ASSIGNED, null);
        assertThat(label).isEqualTo(CustomerStateLabels.ASSIGNED);
        assertThat(label.getLabel()).doesNotContain("ASSIGNED");
    }

    @Test
    @DisplayName("AC-7: EN_ROUTE state label contains no internal code")
    void enRouteState_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.EN_ROUTE, null);
        assertThat(label).isEqualTo(CustomerStateLabels.EN_ROUTE);
        assertThat(label.getLabel()).doesNotContain("EN_ROUTE");
    }

    @Test
    @DisplayName("AC-7: IN_PROGRESS state label contains no internal code")
    void inProgressState_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.IN_PROGRESS, null);
        assertThat(label).isEqualTo(CustomerStateLabels.IN_PROGRESS);
        assertThat(label.getLabel()).doesNotContain("IN_PROGRESS");
    }

    @Test
    @DisplayName("AC-7: COMPLETED state has non-empty label and description")
    void completedState_hasNonEmptyLabels() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.COMPLETED, null);
        assertThat(label).isEqualTo(CustomerStateLabels.COMPLETED);
        assertThat(label.getLabel()).isNotBlank();
        assertThat(label.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("AC-7: CLOSED state has non-empty label")
    void closedState_hasLabel() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.CLOSED, null);
        assertThat(label).isEqualTo(CustomerStateLabels.CLOSED);
        assertThat(label.getLabel()).isNotBlank();
    }

    @Test
    @DisplayName("AC-7: CANCELLED state has approved cancellation phrase without internal reason codes")
    void cancelledState_noCodes() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.CANCELLED, null);
        assertThat(label).isEqualTo(CustomerStateLabels.CANCELLED);
        assertThat(label.getLabel()).isNotBlank().doesNotContain("CANCELLED");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD with null reason falls back to default hold label")
    void onHold_nullReason_fallsBackToDefault() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, null);
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_DEFAULT);
    }

    @Test
    @DisplayName("AC-7: ON_HOLD AWAITING_PARTS reason — label mentions parts without supplier names")
    void onHold_awaitingParts_noSupplierNames() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "AWAITING_PARTS");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_AWAITING_PARTS);
        assertThat(label.getLabel()).doesNotContain("AWAITING_PARTS");
        assertThat(label.getDescription()).doesNotContain("AWAITING_PARTS");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD CUSTOMER_UNAVAILABLE reason — label does not expose internal code")
    void onHold_customerUnavailable_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "CUSTOMER_UNAVAILABLE");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_CUSTOMER_UNAVAILABLE);
        assertThat(label.getLabel()).doesNotContain("CUSTOMER_UNAVAILABLE");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD ACCESS_DENIED reason — label does not expose internal code")
    void onHold_accessDenied_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "ACCESS_DENIED");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_ACCESS_DENIED);
        assertThat(label.getLabel()).doesNotContain("ACCESS_DENIED");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD WEATHER reason — label does not expose internal code")
    void onHold_weather_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "WEATHER");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_WEATHER);
        assertThat(label.getLabel()).doesNotContain("WEATHER");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD SAFETY_CONCERN reason — label does not expose internal code")
    void onHold_safetyConcern_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "SAFETY_CONCERN");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_SAFETY_CONCERN);
        assertThat(label.getLabel()).doesNotContain("SAFETY_CONCERN");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD AWAITING_APPROVAL reason — label does not expose internal code")
    void onHold_awaitingApproval_noInternalCode() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "AWAITING_APPROVAL");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_AWAITING_APPROVAL);
        assertThat(label.getLabel()).doesNotContain("AWAITING_APPROVAL");
    }

    @Test
    @DisplayName("AC-7: ON_HOLD unknown reason code falls back to legacy other label")
    void onHold_unknownCode_fallsBackToLegacyOther() {
        CustomerStateLabels label = CustomerStateLabels.resolve(WorkOrderStatus.ON_HOLD, "SOME_UNKNOWN_CODE");
        assertThat(label).isEqualTo(CustomerStateLabels.ON_HOLD_LEGACY_OTHER);
    }

    @Test
    @DisplayName("AC-7: Every WorkOrderStatus has a non-null, non-empty label")
    void allStatuses_haveNonEmptyLabel() {
        for (WorkOrderStatus status : WorkOrderStatus.values()) {
            CustomerStateLabels label = CustomerStateLabels.resolve(status, null);
            assertThat(label.getLabel())
                    .as("Label for state %s", status)
                    .isNotBlank();
            assertThat(label.getDescription())
                    .as("Description for state %s", status)
                    .isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // AC-4 / AC-8: ETag determinism and change detection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: ETag is deterministic — same id and version produce same ETag")
    void etag_isDeterministic() {
        UUID id = UUID.randomUUID();
        String etag1 = PortalStatusService.etag(id, 3);
        String etag2 = PortalStatusService.etag(id, 3);
        assertThat(etag1).isEqualTo(etag2);
    }

    @Test
    @DisplayName("AC-4: ETag changes when version increments")
    void etag_changesOnVersionIncrement() {
        UUID id = UUID.randomUUID();
        assertThat(PortalStatusService.etag(id, 1))
                .isNotEqualTo(PortalStatusService.etag(id, 2));
    }

    @Test
    @DisplayName("AC-4: ETag is surrounded by double quotes (strong ETag format)")
    void etag_isQuoted() {
        UUID id = UUID.randomUUID();
        String etag = PortalStatusService.etag(id, 0);
        assertThat(etag).startsWith("\"").endsWith("\"");
    }

    @Test
    @DisplayName("AC-4: ETags for different work order ids are different")
    void etag_differentIdsProduceDifferentValues() {
        assertThat(PortalStatusService.etag(UUID.randomUUID(), 1))
                .isNotEqualTo(PortalStatusService.etag(UUID.randomUUID(), 1));
    }

    // -------------------------------------------------------------------------
    // AC-2: Redaction allow-list — forbidden fields absent from serialised JSON
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: Serialised PortalStatusView contains none of the forbidden field names")
    void portalStatusView_forbiddenFieldsAbsent() throws Exception {
        PortalStatusView view = new PortalStatusView(
                UUID.randomUUID(),
                "WO-000001",
                "Work in progress",
                "Your engineer is on site.",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                null,  // no appointmentWindow
                null,  // no technician
                List.of(),
                new PortalStatusView.Freshness(Instant.now(), 60, false));

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(view);

        // Forbidden fields that must never appear in the portal JSON
        String[] forbidden = {
                "latitude", "longitude",      // GPS breadcrumbs
                "technicianPhone", "phone",    // technician contact
                "email",                       // technician email
                "fullName",                    // technician full name
                "employeeCode", "employeeId",  // employee identifier
                "internalState", "rawState",   // raw state enum values
                "score", "dispatchScore",      // dispatch scores
                "overrideReason",              // override reasons
                "cost", "labour", "parts",     // cost data
                "slaPolicyId", "appliedSla",   // internal SLA references
                "customerId", "accountId"      // internal account identifiers
        };

        for (String field : forbidden) {
            assertThat(json)
                    .as("JSON must not contain forbidden field '%s'", field)
                    .doesNotContain("\"" + field + "\"");
        }
    }

    @Test
    @DisplayName("AC-3: TechnicianSummary contains only firstName and roleLabel — no full name, phone, email")
    void technicianSummary_onlyPermittedFields() throws Exception {
        PortalStatusView view = new PortalStatusView(
                UUID.randomUUID(),
                "WO-000002",
                "Engineer on the way",
                "Description.",
                null,
                null,
                null,
                new PortalStatusView.TechnicianSummary("Alex", "Field Engineer"),
                List.of(),
                new PortalStatusView.Freshness(Instant.now(), 60, false));

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(view);

        // Must contain
        assertThat(json).contains("\"firstName\"").contains("\"roleLabel\"");

        // Must NOT contain
        assertThat(json).doesNotContain("\"fullName\"")
                        .doesNotContain("\"phone\"")
                        .doesNotContain("\"email\"")
                        .doesNotContain("\"employeeCode\"")
                        .doesNotContain("\"id\"");
    }

    // -------------------------------------------------------------------------
    // AC-5: Freshness — degraded flag logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: Freshness.staleAfterSeconds is 60 (matches polling cadence)")
    void freshness_staleAfterSecondsIs60() {
        PortalStatusView.Freshness freshness =
                new PortalStatusView.Freshness(Instant.now(), 60, false);
        assertThat(freshness.staleAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("AC-5: Freshness.degraded=false when projection is fresh")
    void freshness_notDegradedByDefault() {
        PortalStatusView.Freshness freshness =
                new PortalStatusView.Freshness(Instant.now(), 60, false);
        assertThat(freshness.degraded()).isFalse();
    }

    @Test
    @DisplayName("AC-5: Freshness.degraded=true signals stale source to client")
    void freshness_degradedTrueSignalledCorrectly() {
        PortalStatusView.Freshness freshness =
                new PortalStatusView.Freshness(Instant.now().minusSeconds(90), 60, true);
        assertThat(freshness.degraded()).isTrue();
        assertThat(freshness.observedAt()).isBefore(Instant.now().minusSeconds(59));
    }
}
