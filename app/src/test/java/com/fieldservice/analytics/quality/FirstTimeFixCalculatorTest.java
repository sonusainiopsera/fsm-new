package com.fieldservice.analytics.quality;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.analytics.internal.quality.CohortMaturityResolver;
import com.fieldservice.analytics.internal.quality.FirstTimeFixCalculator;
import com.fieldservice.analytics.internal.quality.RepeatVisitLinker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the first-time fix quality analytics.
 *
 * <p>No Spring Boot application context — dependencies injected directly.
 */
@ExtendWith(MockitoExtension.class)
class FirstTimeFixCalculatorTest {

    static final Instant NOW = Instant.parse("2025-04-15T12:00:00Z");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    JdbcTemplate analyticsJdbc;

    FirstTimeFixCalculator calculator;
    CohortMaturityResolver maturityResolver;

    @BeforeEach
    void setUp() {
        calculator      = new FirstTimeFixCalculator(analyticsJdbc, clock);
        maturityResolver = new CohortMaturityResolver(clock);
    }

    // ---------------------------------------------------------------
    // CohortMaturityResolver — pure clock logic
    // ---------------------------------------------------------------

    @Test
    @DisplayName("WO closed 29 days ago is PROVISIONAL")
    void maturity_provisional_if_closed_29_days_ago() {
        Instant closedAt = NOW.minus(29, ChronoUnit.DAYS);
        assertThat(maturityResolver.resolve(closedAt)).isEqualTo("PROVISIONAL");
    }

    @Test
    @DisplayName("WO closed exactly 30 days ago is MATURED")
    void maturity_matured_at_exactly_30_days() {
        Instant closedAt = NOW.minus(30, ChronoUnit.DAYS);
        assertThat(maturityResolver.resolve(closedAt)).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("WO closed 31 days ago is MATURED")
    void maturity_matured_if_closed_31_days_ago() {
        Instant closedAt = NOW.minus(31, ChronoUnit.DAYS);
        assertThat(maturityResolver.resolve(closedAt)).isEqualTo("MATURED");
    }

    // ---------------------------------------------------------------
    // Repeat-visit window boundary semantics
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Later closure at 29 days IS a repeat visit")
    void repeatWindow_29days_isRepeat() {
        Instant earlier = NOW.minus(40, ChronoUnit.DAYS);
        Instant later   = earlier.plus(29, ChronoUnit.DAYS);
        assertThat(maturityResolver.isWithinRepeatWindow(earlier, later)).isTrue();
    }

    @Test
    @DisplayName("Later closure at exactly 30 days is NOT a repeat visit")
    void repeatWindow_30days_isNotRepeat() {
        Instant earlier = NOW.minus(40, ChronoUnit.DAYS);
        Instant later   = earlier.plus(30, ChronoUnit.DAYS);
        assertThat(maturityResolver.isWithinRepeatWindow(earlier, later)).isFalse();
    }

    @Test
    @DisplayName("Later closure at 31 days is NOT a repeat visit")
    void repeatWindow_31days_isNotRepeat() {
        Instant earlier = NOW.minus(40, ChronoUnit.DAYS);
        Instant later   = earlier.plus(31, ChronoUnit.DAYS);
        assertThat(maturityResolver.isWithinRepeatWindow(earlier, later)).isFalse();
    }

    @Test
    @DisplayName("Earlier closure at same timestamp is not a repeat (zero gap)")
    void repeatWindow_sameTime_isNotRepeat() {
        Instant t = NOW.minus(10, ChronoUnit.DAYS);
        assertThat(maturityResolver.isWithinRepeatWindow(t, t)).isFalse();
    }

    // ---------------------------------------------------------------
    // fault_key derivation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("fault_code present — used as fault_key")
    void faultKey_usesFaultCode_whenPresent() {
        assertThat(RepeatVisitLinker.deriveFaultKey("FC-001", "HVAC")).isEqualTo("FC-001");
    }

    @Test
    @DisplayName("fault_code absent — fault_category normalised to UPPER")
    void faultKey_usesFaultCategory_whenCodeAbsent() {
        assertThat(RepeatVisitLinker.deriveFaultKey(null, "hvac cooling")).isEqualTo("HVAC COOLING");
    }

    @Test
    @DisplayName("Both absent — null (UNCLASSIFIABLE)")
    void faultKey_null_whenBothAbsent() {
        assertThat(RepeatVisitLinker.deriveFaultKey(null, null)).isNull();
        assertThat(RepeatVisitLinker.deriveFaultKey("", "  ")).isNull();
    }

    // ---------------------------------------------------------------
    // FirstTimeFixCalculator — matured rate arithmetic (mocked JdbcTemplate)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Matured rate computed correctly: 7 of 10 WOs are first-time fix")
    void compute_maturedRate_basicArithmetic() {
        Timestamp asOf = Timestamp.from(NOW.minus(1, ChronoUnit.HOURS));
        // For the P90D ALL segment query
        when(analyticsJdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("denominator", 10L, "numerator", 7L, "data_as_of", asOf));
        when(analyticsJdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        assertThat(results).isNotEmpty();
        KpiAggregator.KpiAggregatorResult r = results.stream()
                .filter(x -> "ALL".equals(x.segmentKey()) && "P90D".equals(x.windowKey()))
                .findFirst().orElseThrow();
        assertThat(r.denominator()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(r.numerator()).isEqualByComparingTo(BigDecimal.valueOf(7));
        assertThat(r.value()).isEqualByComparingTo(new BigDecimal("0.7000"));
        assertThat(r.maturity()).isEqualTo("MATURE");
    }

    @Test
    @DisplayName("Zero denominator — value is null (no-data, not 0%)")
    void compute_zeroDenominator_valueIsNull() {
        when(analyticsJdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("denominator", 0L, "numerator", 0L,
                                   "data_as_of", Timestamp.from(NOW)));
        when(analyticsJdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        results.stream()
                .filter(r -> "ALL".equals(r.segmentKey()))
                .forEach(r -> assertThat(r.value()).isNull());
    }

    @Test
    @DisplayName("100% first-time fix — value is 1.0000")
    void compute_allFirstTimeFix_valueIsOne() {
        Timestamp asOf = Timestamp.from(NOW.minus(5, ChronoUnit.DAYS));
        when(analyticsJdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("denominator", 50L, "numerator", 50L, "data_as_of", asOf));
        when(analyticsJdbc.queryForList(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(List.of());

        List<KpiAggregator.KpiAggregatorResult> results = calculator.compute();

        KpiAggregator.KpiAggregatorResult r = results.stream()
                .filter(x -> "ALL".equals(x.segmentKey()) && "P90D".equals(x.windowKey()))
                .findFirst().orElseThrow();
        assertThat(r.value()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("metricKey returns the correct stable key")
    void metricKey_correctValue() {
        assertThat(calculator.metricKey()).isEqualTo("quality.first_time_fix.matured");
    }

    // ---------------------------------------------------------------
    // maturedAt calculation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("maturedAt is exactly 30 days after closedAt")
    void maturedAt_exactly30DaysAfterClosure() {
        Instant closedAt  = Instant.parse("2025-01-01T10:00:00Z");
        Instant expected  = Instant.parse("2025-01-31T10:00:00Z");
        assertThat(maturityResolver.maturedAt(closedAt)).isEqualTo(expected);
    }

    // ---------------------------------------------------------------
    // Chain-of-3 repeat visit handling
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Chain of 3: each interval pair is within the 30-day window")
    void chain_threeVisits_eachPairInWindow() {
        Instant first  = NOW.minus(60, ChronoUnit.DAYS);
        Instant second = first.plus(10, ChronoUnit.DAYS);
        Instant third  = second.plus(15, ChronoUnit.DAYS);

        // First→Second: 10 days, within window
        assertThat(maturityResolver.isWithinRepeatWindow(first,  second)).isTrue();
        // Second→Third: 15 days, within window
        assertThat(maturityResolver.isWithinRepeatWindow(second, third)).isTrue();
        // First→Third: 25 days, still within window (but linked via predecessor)
        assertThat(maturityResolver.isWithinRepeatWindow(first, third)).isTrue();
    }
}
