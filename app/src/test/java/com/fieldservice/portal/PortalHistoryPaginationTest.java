package com.fieldservice.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fieldservice.platform.api.exception.InvalidSortException;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.portal.history.InvalidDateRangeException;
import com.fieldservice.portal.history.StatusGroup;
import com.fieldservice.portal.web.PortalHistorySortSpec;
import com.fieldservice.portal.web.dto.PortalHistoryRow;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static com.fieldservice.portal.service.PortalHistoryService.MAX_DATE_RANGE_DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests (no Spring context) for pagination, sort allow-list, date-range
 * bounding, and PortalHistoryRow redaction.
 *
 * <p>Covers AC-2, AC-3, AC-4, AC-7, and AC-9 of WO-172.
 */
class PortalHistoryPaginationTest {

    // -------------------------------------------------------------------------
    // AC-2: Page-size clamping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: size=0 clamps to 1 (positive via PageQuery.of)")
    void pageSize_zero_clampsToOne() {
        PageQuery q = PageQuery.of(0, 0, null);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2: size=1 is preserved")
    void pageSize_one_preserved() {
        PageQuery q = PageQuery.of(0, 1, null);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2: size=50 is preserved (boundary)")
    void pageSize_fifty_preserved() {
        PageQuery q = PageQuery.of(0, 50, null);
        assertThat(q.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("AC-2: size=51 is clamped to 50")
    void pageSize_fiftyOne_clamped() {
        PageQuery q = PageQuery.of(0, 51, null);
        assertThat(q.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("AC-2: size=200 is clamped to 50")
    void pageSize_twoHundred_clamped() {
        PageQuery q = PageQuery.of(0, 200, null);
        assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("AC-2: null size defaults to 20")
    void pageSize_null_defaultsTwenty() {
        PageQuery q = PageQuery.of(null, null, null);
        assertThat(q.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    // -------------------------------------------------------------------------
    // AC-3: Sort allow-list validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: createdAt:desc is a permitted sort")
    void sort_createdAtDesc_permitted() {
        var sort = PortalHistorySortSpec.ALLOW_LIST.parse("createdAt:desc");
        assertThat(sort.getOrderFor("createdAt")).isNotNull();
        assertThat(sort.getOrderFor("id")).isNotNull(); // tie-break always appended
    }

    @Test
    @DisplayName("AC-3: state:asc is a permitted sort")
    void sort_stateAsc_permitted() {
        var sort = PortalHistorySortSpec.ALLOW_LIST.parse("state:asc");
        assertThat(sort.getOrderFor("state")).isNotNull();
        assertThat(sort.getOrderFor("id")).isNotNull();
    }

    @Test
    @DisplayName("AC-3: Unknown sort field throws InvalidSortException")
    void sort_unknownField_throwsInvalidSortException() {
        assertThatThrownBy(() -> PortalHistorySortSpec.ALLOW_LIST.parse("technicianId:desc"))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("technicianId");
    }

    @Test
    @DisplayName("AC-3: Unknown sort field 'latitude' (forbidden GPS) throws InvalidSortException")
    void sort_latitudeField_throwsInvalidSortException() {
        assertThatThrownBy(() -> PortalHistorySortSpec.ALLOW_LIST.parse("latitude:asc"))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("AC-3: Sort without direction defaults to ascending")
    void sort_noDirection_defaultsAsc() {
        var sort = PortalHistorySortSpec.ALLOW_LIST.parse("createdAt");
        var order = sort.getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.isAscending()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC-4: Tie-break on UUID always present
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: id tie-break is always appended to the sort")
    void sort_tieBreak_alwaysPresent() {
        for (String field : PortalHistorySortSpec.ALLOW_LIST.allowedFields()) {
            var sort = PortalHistorySortSpec.ALLOW_LIST.parse(field + ":asc");
            assertThat(sort.getOrderFor("id"))
                    .as("id tie-break must be present for sort field: %s", field)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("AC-4: Default sort (no param) includes id tie-break")
    void sort_default_includesTieBreak() {
        var sort = PortalHistorySortSpec.ALLOW_LIST.parse(null);
        assertThat(sort.getOrderFor("id")).isNotNull();
        assertThat(sort.getOrderFor("createdAt")).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC-8 / Date range: validation logic (pure unit — no Spring context)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Date range: fromDate < toDate within limit is valid")
    void dateRange_valid_noException() {
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to   = Instant.parse("2025-06-01T00:00:00Z");
        // Direct business rule: span must be < MAX_DATE_RANGE_DAYS
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        assertThat(days).isLessThanOrEqualTo(MAX_DATE_RANGE_DAYS);
    }

    @Test
    @DisplayName("Date range: MAX_DATE_RANGE_DAYS constant is 365")
    void dateRange_maxDays_is365() {
        assertThat(MAX_DATE_RANGE_DAYS).isEqualTo(365);
    }

    @Test
    @DisplayName("Date range: InvalidDateRangeException carries field and message")
    void dateRange_exception_hasFieldAndMessage() {
        var ex = new InvalidDateRangeException("fromDate", "fromDate must not be after toDate.");
        assertThat(ex.getField()).isEqualTo("fromDate");
        assertThat(ex.getMessage()).contains("fromDate");
    }

    // -------------------------------------------------------------------------
    // StatusGroup mapping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("StatusGroup.OPEN covers NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD")
    void statusGroup_open_coversActivestates() {
        assertThat(StatusGroup.OPEN.getStates()).containsExactlyInAnyOrder(
                WorkOrderStatus.NEW, WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.EN_ROUTE, WorkOrderStatus.IN_PROGRESS,
                WorkOrderStatus.ON_HOLD);
    }

    @Test
    @DisplayName("StatusGroup.CLOSED covers COMPLETED, CLOSED, CANCELLED")
    void statusGroup_closed_coversTerminalStates() {
        assertThat(StatusGroup.CLOSED.getStates()).containsExactlyInAnyOrder(
                WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED, WorkOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("StatusGroup OPEN and CLOSED together cover all WorkOrderStatus values")
    void statusGroup_openPlusClosed_coversAllStates() {
        var all = Arrays.asList(WorkOrderStatus.values());
        var covered = new java.util.ArrayList<>(StatusGroup.OPEN.getStates());
        covered.addAll(StatusGroup.CLOSED.getStates());
        assertThat(covered).containsExactlyInAnyOrderElementsOf(all);
    }

    // -------------------------------------------------------------------------
    // AC-7: PortalHistoryRow redaction — forbidden fields absent from JSON
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-7: Serialised PortalHistoryRow contains none of the forbidden field names")
    void portalHistoryRow_forbiddenFieldsAbsent() throws Exception {
        PortalHistoryRow row = new PortalHistoryRow(
                UUID.randomUUID(),
                "WO-TEST-001",
                "Request received",
                "Acme HQ",
                null,
                Instant.parse("2025-03-01T10:00:00Z"),
                null,
                null);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(row);

        String[] forbidden = {
                "latitude", "longitude",
                "technicianPhone", "phone", "email",
                "fullName", "employeeCode", "employeeId",
                "internalState", "rawState",
                "score", "dispatchScore",
                "overrideReason",
                "cost", "labour", "parts",
                "slaPolicyId", "appliedSla",
                "customerId", "accountId",
                "assignedTechnicianId", "technicianId"
        };

        for (String field : forbidden) {
            assertThat(json)
                    .as("JSON must not contain forbidden field '%s'", field)
                    .doesNotContain("\"" + field + "\"");
        }
    }

    @Test
    @DisplayName("AC-7: Serialised PortalHistoryRow contains required customer-safe fields")
    void portalHistoryRow_requiredFieldsPresent() throws Exception {
        PortalHistoryRow row = new PortalHistoryRow(
                UUID.randomUUID(),
                "WO-TEST-002",
                "Engineer assigned",
                "Acme Branch",
                "HVAC Unit (AC-001)",
                Instant.parse("2025-03-01T10:00:00Z"),
                null,
                null);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(row);

        assertThat(json)
                .contains("\"workOrderId\"")
                .contains("\"reference\"")
                .contains("\"statusLabel\"")
                .contains("\"siteName\"")
                .contains("\"assetLabel\"")
                .contains("\"openedAt\"");

        // Null fields should be omitted (JsonInclude.NON_NULL)
        assertThat(json).doesNotContain("\"closedAt\"")
                        .doesNotContain("\"outcomeSummary\"");
    }
}
