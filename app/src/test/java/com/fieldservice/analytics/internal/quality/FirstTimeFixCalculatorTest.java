package com.fieldservice.analytics.internal.quality;

import com.fieldservice.analytics.internal.FirstTimeFixCalculator;
import com.fieldservice.analytics.internal.KpiAggregationQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FirstTimeFixCalculator} (WO-164).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FirstTimeFixCalculator unit tests")
class FirstTimeFixCalculatorTest {

    @Mock
    private JdbcTemplate replicaJdbcTemplate;

    private FirstTimeFixCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FirstTimeFixCalculator(replicaJdbcTemplate);
    }

    @Test
    @DisplayName("AC-1: matured FTF rate = ftf_count / total when cohort non-empty")
    void maturedRate_returnsRatio_whenCohortNonEmpty() {
        when(replicaJdbcTemplate.queryForMap(anyString(), (Object) any()))
                .thenReturn(Map.of("ftf_count", 8L, "total", 10L));

        KpiAggregationQueries.AggregateResult result = calculator.queryMaturedRate();

        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualByComparingTo(new BigDecimal("0.8000"));
        assertThat(result.numerator()).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(result.denominator()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(result.sampleCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC-8: empty matured cohort returns null (no-data), not 0%")
    void maturedRate_returnsNull_whenEmptyCohort() {
        when(replicaJdbcTemplate.queryForMap(anyString(), (Object) any()))
                .thenReturn(Map.of("ftf_count", 0L, "total", 0L));

        KpiAggregationQueries.AggregateResult result = calculator.queryMaturedRate();

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("BR-30: provisional rate computed the same way as matured rate")
    void provisionalRate_returnsRatio_whenCohortNonEmpty() {
        when(replicaJdbcTemplate.queryForMap(anyString(), (Object) any()))
                .thenReturn(Map.of("ftf_count", 3L, "total", 5L));

        KpiAggregationQueries.AggregateResult result = calculator.queryProvisionalRate();

        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualByComparingTo(new BigDecimal("0.6000"));
    }

    @Test
    @DisplayName("AC-8: empty provisional cohort returns null")
    void provisionalRate_returnsNull_whenEmptyCohort() {
        when(replicaJdbcTemplate.queryForMap(anyString(), (Object) any()))
                .thenReturn(Map.of("ftf_count", 0L, "total", 0L));

        assertThat(calculator.queryProvisionalRate()).isNull();
    }

    @Test
    @DisplayName("AC-5: repeat visit count returns all-time count")
    void repeatVisitCount_returnsCount() {
        when(replicaJdbcTemplate.queryForObject(anyString(), (Class<?>) any()))
                .thenReturn(7L);

        KpiAggregationQueries.AggregateResult result = calculator.queryRepeatVisitCount();

        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(7));
    }

    @Test
    @DisplayName("AC-5: unclassifiable count returns count of null-fault-key rows")
    void unclassifiableCount_returnsCount() {
        when(replicaJdbcTemplate.queryForObject(anyString(), (Class<?>) any()))
                .thenReturn(3L);

        KpiAggregationQueries.AggregateResult result = calculator.queryUnclassifiableCount();

        assertThat(result.value()).isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    @Test
    @DisplayName("AC-8: null DB count treated as zero for repeat visit count")
    void repeatVisitCount_handlesNullResult() {
        when(replicaJdbcTemplate.queryForObject(anyString(), (Class<?>) any()))
                .thenReturn(null);

        KpiAggregationQueries.AggregateResult result = calculator.queryRepeatVisitCount();
        assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
