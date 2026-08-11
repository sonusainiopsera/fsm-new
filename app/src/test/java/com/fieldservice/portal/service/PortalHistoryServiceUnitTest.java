package com.fieldservice.portal.service;

import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.SortField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Pure unit tests for sort validation and date-range validation in {@link PortalHistoryService}.
 * No Spring context — directly exercises package-private helpers (AC-3, AC-2, date-range).
 */
class PortalHistoryServiceUnitTest {

    private PortalHistoryService service;

    @BeforeEach
    void setUp() {
        // Inject mocks for the two collaborators; the tested methods do not use them
        service = new PortalHistoryService(
                mock(com.fieldservice.portal.access.CustomerAccessScope.class),
                mock(jakarta.persistence.EntityManager.class)
        );
        // Default maxDateRangeDays matches the production default
        service.maxDateRangeDays = 366;
    }

    // ── Sort allow-list (AC-3) ────────────────────────────────────────────────

    @Test
    @DisplayName("createdAt is an approved sort field")
    void createdAt_isAllowed() {
        List<String> resolved = service.resolveAndValidateSorts(
                List.of(new SortField("createdAt", Sort.Direction.DESC)));
        assertThat(resolved).containsExactly("wo.createdAt");
    }

    @Test
    @DisplayName("state is an approved sort field")
    void state_isAllowed() {
        List<String> resolved = service.resolveAndValidateSorts(
                List.of(new SortField("state", Sort.Direction.ASC)));
        assertThat(resolved).containsExactly("wo.state");
    }

    @Test
    @DisplayName("closedAt is an approved sort field (maps to wo.updatedAt)")
    void closedAt_isAllowed() {
        List<String> resolved = service.resolveAndValidateSorts(
                List.of(new SortField("closedAt", Sort.Direction.DESC)));
        assertThat(resolved).containsExactly("wo.updatedAt");
    }

    @Test
    @DisplayName("Unknown sort field throws InvalidSortException — no query built (AC-3)")
    void unknownField_throwsInvalidSortException() {
        assertThatThrownBy(() ->
                service.resolveAndValidateSorts(
                        List.of(new SortField("description", Sort.Direction.ASC))))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("JPQL property path not accepted as a public sort name (injection defence)")
    void jpqlPath_isRejected() {
        assertThatThrownBy(() ->
                service.resolveAndValidateSorts(
                        List.of(new SortField("wo.createdAt", Sort.Direction.ASC))))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("SQL injection attempt via sort name is rejected")
    void sqlInjection_isRejected() {
        assertThatThrownBy(() ->
                service.resolveAndValidateSorts(
                        List.of(new SortField("1;DROP TABLE work_order", Sort.Direction.ASC))))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("Empty sort list is accepted (default sort applied in buildOrderByClause)")
    void emptySort_isAccepted() {
        List<String> resolved = service.resolveAndValidateSorts(List.of());
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("Multiple valid sort fields are all resolved")
    void multipleSorts_areAllResolved() {
        List<String> resolved = service.resolveAndValidateSorts(List.of(
                new SortField("createdAt", Sort.Direction.DESC),
                new SortField("state", Sort.Direction.ASC)));
        assertThat(resolved).containsExactly("wo.createdAt", "wo.state");
    }

    // ── Date range validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("Valid date range with both bounds is accepted")
    void validDateRange_isAccepted() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to   = LocalDate.of(2026, 6, 30);
        // Must not throw
        service.validateDateRange(from, to);
    }

    @Test
    @DisplayName("Null from/to dates are accepted (open-ended range)")
    void nullDates_areAccepted() {
        service.validateDateRange(null, null);
        service.validateDateRange(LocalDate.of(2026, 1, 1), null);
        service.validateDateRange(null, LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("Same date for from and to is accepted (single day)")
    void sameDateRange_isAccepted() {
        LocalDate date = LocalDate.of(2026, 3, 15);
        service.validateDateRange(date, date);
    }

    @Test
    @DisplayName("Inverted range (fromDate after toDate) throws DateRangeException → 400")
    void invertedRange_throwsDateRangeException() {
        LocalDate from = LocalDate.of(2026, 6, 30);
        LocalDate to   = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> service.validateDateRange(from, to))
                .isInstanceOf(PortalHistoryService.DateRangeException.class)
                .hasMessageContaining("fromDate must not be after toDate");
    }

    @Test
    @DisplayName("Date range exceeding 366 days throws DateRangeException → 400")
    void tooWideDateRange_throwsDateRangeException() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to   = LocalDate.of(2026, 6, 30); // ~545 days

        assertThatThrownBy(() -> service.validateDateRange(from, to))
                .isInstanceOf(PortalHistoryService.DateRangeException.class)
                .hasMessageContaining("exceeds the maximum");
    }

    @Test
    @DisplayName("Date range of exactly maxDateRangeDays days is accepted")
    void exactMaxRange_isAccepted() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to   = from.plusDays(366); // exactly maxDateRangeDays
        // Must not throw
        service.validateDateRange(from, to);
    }

    // ── PageQuery size clamping (AC-2) ────────────────────────────────────────

    @Test
    @DisplayName("PageQuery clamps oversized size to MAX_SIZE=50 (AC-2)")
    void pageQuery_clampsOversizeToMax() {
        PageQuery pq = new PageQuery(0, 200, List.of(), null);
        assertThat(pq.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("PageQuery preserves size at MAX_SIZE=50")
    void pageQuery_acceptsExactMax() {
        PageQuery pq = new PageQuery(0, 50, List.of(), null);
        assertThat(pq.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("PageQuery clamps size below 1 to 1")
    void pageQuery_clampsZeroSizeToOne() {
        PageQuery pq = new PageQuery(0, 0, List.of(), null);
        assertThat(pq.size()).isEqualTo(1);
    }
}
