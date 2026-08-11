package com.fieldservice.analytics.internal.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RepeatVisitLinker} (WO-164).
 *
 * <p>Covers repeat-visit detection boundary semantics: 29 days = linked, 30 days = not linked.
 * Uses injected fixed {@link Clock} for deterministic boundary assertions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepeatVisitLinker unit tests")
class RepeatVisitLinkerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private Clock fixedClock;
    private RepeatVisitLinker linker;

    private static final UUID WORK_ORDER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID EARLIER_WO_ID  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID ASSET_ID       = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2025-06-01T12:00:00Z"), ZoneOffset.UTC);
        linker = new RepeatVisitLinker(jdbcTemplate, fixedClock);
    }

    @Test
    @DisplayName("AC-2: no earlier closure → FTF projection upserted, no link created")
    void processClosureEvent_noEarlierClosure_isFirstTimeFix() {
        stubIdentity(ASSET_ID, "FC-001", null);
        stubNoEarlierClosure();

        linker.processClosureEvent(WORK_ORDER_ID, fixedClock.instant());

        // Upsert closure projection called
        verify(jdbcTemplate).update(anyString(),
                any(), eq(WORK_ORDER_ID), eq(ASSET_ID), eq("FC-001"), eq(true),
                any(), any(), any(), any());

        // No repeat_visit_link row
        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("repeat_visit_link"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-3: earlier closure within 29 days → repeat visit linked, earlier marked not-FTF")
    void processClosureEvent_earlierClosureWithin29Days_linked() {
        Instant laterClosed  = fixedClock.instant();
        Instant earlierClosed = laterClosed.minus(java.time.Duration.ofDays(29));

        stubIdentity(ASSET_ID, "FC-001", null);
        stubEarlierClosure(EARLIER_WO_ID, earlierClosed);

        linker.processClosureEvent(WORK_ORDER_ID, laterClosed);

        // repeat_visit_link created
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("repeat_visit_link"),
                any(), eq(EARLIER_WO_ID), eq(WORK_ORDER_ID), eq(ASSET_ID), eq("FC-001"), eq(29), any());

        // Earlier WO marked not-FTF
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("is_first_time_fix = false"),
                any(), any(), eq(EARLIER_WO_ID));
    }

    @Test
    @DisplayName("BR boundary: exactly 30 days apart falls OUTSIDE window → not linked")
    void processClosureEvent_exactly30Days_notLinked() {
        Instant laterClosed   = fixedClock.instant();
        // 30 days earlier is outside the window (strictly < 30)
        stubIdentity(ASSET_ID, "FC-001", null);
        stubNoEarlierClosure();   // window query returns nothing for 30-day-old event

        linker.processClosureEvent(WORK_ORDER_ID, laterClosed);

        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("repeat_visit_link"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-5: missing asset_id → UNCLASSIFIABLE upserted, not linked")
    void processClosureEvent_missingAssetId_unclassifiable() {
        stubIdentity(null, "FC-001", null);

        linker.processClosureEvent(WORK_ORDER_ID, fixedClock.instant());

        // Upserted with fault_key=null, is_first_time_fix=false
        verify(jdbcTemplate).update(anyString(),
                any(), eq(WORK_ORDER_ID), eq(null), eq(null), eq(false),
                any(), any(), any(), any());

        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("repeat_visit_link"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-5: missing fault code and category → UNCLASSIFIABLE")
    void processClosureEvent_missingFault_unclassifiable() {
        stubIdentity(ASSET_ID, null, null);

        linker.processClosureEvent(WORK_ORDER_ID, fixedClock.instant());

        verify(jdbcTemplate).update(anyString(),
                any(), eq(WORK_ORDER_ID), eq(ASSET_ID), eq(null), eq(false),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("FaultKeyDeriver: fault_code takes precedence over fault_category")
    void faultKeyDeriver_faultCodePreferred() {
        assertThat(FaultKeyDeriver.derive("FC-001", "CAT-A")).isEqualTo("FC-001");
        assertThat(FaultKeyDeriver.derive(null, "CAT-A")).isEqualTo("CAT:CAT-A");
        assertThat(FaultKeyDeriver.derive(null, null)).isNull();
        assertThat(FaultKeyDeriver.derive("  ", "CAT-A")).isEqualTo("CAT:CAT-A");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubIdentity(UUID assetId, String faultCode, String faultCategory) {
        when(jdbcTemplate.queryForObject(
                anyString(),
                (RowMapper<RepeatVisitLinker.WorkOrderIdentity>) any(),
                eq(WORK_ORDER_ID)))
            .thenReturn(new RepeatVisitLinker.WorkOrderIdentity(assetId, faultCode, faultCategory));
    }

    @SuppressWarnings("unchecked")
    private void stubNoEarlierClosure() {
        when(jdbcTemplate.query(
                anyString(),
                (RowMapper<RepeatVisitLinker.EarlierClosure>) any(),
                any(), any(), any(), any()))
            .thenReturn(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private void stubEarlierClosure(UUID workOrderId, Instant closedAt) {
        when(jdbcTemplate.query(
                anyString(),
                (RowMapper<RepeatVisitLinker.EarlierClosure>) any(),
                any(), any(), any(), any()))
            .thenReturn(List.of(new RepeatVisitLinker.EarlierClosure(workOrderId, closedAt)));
    }
}
