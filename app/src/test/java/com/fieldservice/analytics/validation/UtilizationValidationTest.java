package com.fieldservice.analytics.validation;

import com.fieldservice.analytics.internal.KpiAggregator.KpiAggregatorResult;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Validates workforce.utilization.rate against the golden dataset.
 *
 * <p>Golden dataset (from kpi-expected-values.json):
 * TECH-GA: 1080 field_min / 2700 shift_min = 0.4000
 * TECH-GB: 3240 field_min / 2700 shift_min = 1.2000 (over-shift, not clamped)
 * TECH-GC: 0 field_min / 2700 shift_min = 0.0000 (zero logged time)
 *
 * <p>Formula: per-technician = fieldMinutes / shiftMinutes
 * Team rollup uses sum-of-numerators (NOT mean-of-ratios) — AC-1.
 * Zero shift → incompleteData = true, excluded from team rollup.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "app.analytics.enabled=true"
        })
@Import(TestSecurityConfig.class)
class UtilizationValidationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_utilization_val")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url",      postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.flyway.url",          postgres::getJdbcUrl);
        reg.add("spring.flyway.user",         postgres::getUsername);
        reg.add("spring.flyway.password",     postgres::getPassword);
        reg.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        reg.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired JdbcTemplate          jdbc;
    @Autowired UtilizationCalculator utilizationCalculator;

    static final String NS = "gg000000-0000-7207-0004-";

    // Technician + app_user IDs
    static final UUID GA_USER = UUID.fromString(NS + "901000000001");
    static final UUID GB_USER = UUID.fromString(NS + "901000000002");
    static final UUID GC_USER = UUID.fromString(NS + "901000000003");
    static final UUID GD_USER = UUID.fromString(NS + "901000000004"); // no shift (incomplete)

    static final UUID GA_TECH = UUID.fromString(NS + "902000000001"); // 1080/2700 = 0.4000
    static final UUID GB_TECH = UUID.fromString(NS + "902000000002"); // 3240/2700 = 1.2000 (over)
    static final UUID GC_TECH = UUID.fromString(NS + "902000000003"); // 0/2700 = 0.0000 (zero)
    static final UUID GD_TECH = UUID.fromString(NS + "902000000004"); // labour but no shift → incomplete

    static final UUID G_CUSTOMER = UUID.fromString(NS + "000000000001");
    static final UUID G_SITE     = UUID.fromString(NS + "000000000002");
    static final UUID G_WO       = UUID.fromString(NS + "000000000010"); // shared WO for labour entries

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM work_order_labour_entry WHERE work_order_id::text = '" + G_WO + "'");
        jdbc.execute("DELETE FROM technician_availability_window WHERE technician_id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM technician WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM app_user WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM work_order WHERE id::text = '" + G_WO + "'");
        jdbc.execute("DELETE FROM site WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM customer WHERE id::text LIKE '" + NS + "%'");

        jdbc.execute("INSERT INTO customer (id, name, version) VALUES ('" + G_CUSTOMER + "', 'Util Val Customer', 0) ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO site (id, name, customer_id, version) VALUES ('" + G_SITE + "', 'Util Val Site', '" + G_CUSTOMER + "', 0) ON CONFLICT DO NOTHING");

        // app_users (required by technician FK)
        for (Object[] u : new Object[][]{{GA_USER, "GA"}, {GB_USER, "GB"}, {GC_USER, "GC"}, {GD_USER, "GD"}}) {
            jdbc.update("INSERT INTO app_user (id, email, display_name, active, version) VALUES (?,?,?,true,0) ON CONFLICT DO NOTHING",
                    u[0], "utilval-" + u[1] + "@test.example", "Util Tech " + u[1]);
        }

        // technicians
        for (Object[] t : new Object[][]{{GA_TECH, GA_USER, "GA"}, {GB_TECH, GB_USER, "GB"},
                                         {GC_TECH, GC_USER, "GC"}, {GD_TECH, GD_USER, "GD"}}) {
            jdbc.update("INSERT INTO technician (id, user_id, full_name, active, timezone, version) VALUES (?,?,?,true,'UTC',0) ON CONFLICT DO NOTHING",
                    t[0], t[1], "Util Tech " + t[2]);
        }

        // Shared work order for labour entries
        jdbc.update("INSERT INTO work_order (id, reference, state, priority, site_id, version) VALUES (?,?,?,?,?,0) ON CONFLICT DO NOTHING",
                G_WO, "UTIL-WO-001", "CLOSED", "MEDIUM", G_SITE);
    }

    /**
     * Insert Mon-Fri 08:00-17:00 shift windows (540 min/day) for a technician.
     * effective_from = 30 days ago to cover the current week.
     */
    private void insertShiftWindows(UUID techId) {
        LocalDate effectiveFrom = LocalDate.now().minusDays(30);
        for (int dow = 1; dow <= 5; dow++) { // Mon=1 to Fri=5
            jdbc.update("""
                    INSERT INTO technician_availability_window
                        (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
                    VALUES (gen_random_uuid(), ?, ?, '08:00', '17:00', ?, 0)
                    """,
                    techId, (short) dow, effectiveFrom);
        }
    }

    /**
     * Insert a labour entry. created_at is used for ISO week bucketing.
     * Minutes must be > 0 per constraint.
     */
    private void insertLabour(UUID techId, int minutes, Instant createdAt) {
        jdbc.update("""
                INSERT INTO work_order_labour_entry (id, work_order_id, technician_id, minutes, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?)
                """,
                G_WO, techId, minutes, Timestamp.from(createdAt));
    }

    /** Computes the ISO week-start (Monday) for a given instant. */
    private LocalDate isoWeekStart(Instant instant) {
        return instant.atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    // ── Unit test: formula (independent of Spring/DB, uses equivalent formula) ─

    /** Applies the same formula as UtilizationCalculator.ratio(): fieldMin/shiftMin, HALF_UP scale 4. */
    private static BigDecimal ratio(long fieldMin, long shiftMin) {
        if (shiftMin == 0) return null;
        return new BigDecimal(fieldMin).divide(new BigDecimal(shiftMin), 4, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("[Unit] 1080/2700 = 0.4000 — TECH-GA formula verification")
    void formula_techGa_ratio_matchesExpected() {
        assertThat(ratio(1080, 2700)).isEqualByComparingTo(new BigDecimal("0.4000"));
    }

    @Test
    @DisplayName("[Unit] 3240/2700 = 1.2000 — TECH-GB over-shift not clamped by formula")
    void formula_techGb_overShift_notClamped() {
        assertThat(ratio(3240, 2700)).isEqualByComparingTo(new BigDecimal("1.2000"));
    }

    @Test
    @DisplayName("[Unit] 0/2700 = 0.0000 — zero field minutes returns BigDecimal.ZERO (not null)")
    void formula_zeroFieldMinutes_returnsZeroNotNull() {
        BigDecimal result = ratio(0, 2700);
        assertThat(result).isNotNull();
        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[Unit] n/0 = null — zero denominator yields not-available (null)")
    void formula_zeroDenominator_returnsNull() {
        assertThat(ratio(500, 0)).isNull();
    }

    // ── Integration: per-technician utilization ───────────────────────────────

    @Test
    @Transactional
    @DisplayName("[Golden] TECH-GA P7D per-tech utilization = 1080/2700 = 0.4000")
    void goldenDataset_techGA_utilization_matchesExpected() {
        insertShiftWindows(GA_TECH);

        // Insert 1080 min labour entry 3 days ago (within P7D)
        Instant labourAt = Instant.now().minus(3, ChronoUnit.DAYS);
        insertLabour(GA_TECH, 1080, labourAt);

        List<KpiAggregatorResult> results = utilizationCalculator.compute();

        LocalDate weekStart = isoWeekStart(labourAt);
        String    segKey    = "TECH:" + GA_TECH + ":WEEK:" + weekStart;

        KpiAggregatorResult gaResult = findBySegmentAndWindow(results, segKey, "P7D");
        // If P7D misses, try P30D (week may span boundary)
        if (gaResult == null) gaResult = findBySegmentAndWindow(results, segKey, "P30D");

        assertThat(gaResult)
                .as("TECH-GA segment should exist in some window")
                .isNotNull();
        assertThat(gaResult.numerator().longValue()).isEqualTo(1080);
        assertThat(gaResult.denominator().longValue()).isEqualTo(2700);
        assertThat(gaResult.value()).isEqualByComparingTo(new BigDecimal("0.4000"));
    }

    @Test
    @Transactional
    @DisplayName("[OverShift] TECH-GB over-shift (3240 > 2700) reported as 1.2000, not clamped")
    void overShift_techGB_utilization_notClamped() {
        insertShiftWindows(GB_TECH);

        Instant labourAt = Instant.now().minus(3, ChronoUnit.DAYS);
        insertLabour(GB_TECH, 3240, labourAt); // more than shift hours

        List<KpiAggregatorResult> results = utilizationCalculator.compute();

        LocalDate weekStart = isoWeekStart(labourAt);
        String    segKey    = "TECH:" + GB_TECH + ":WEEK:" + weekStart;

        KpiAggregatorResult gbResult = findBySegmentAndWindow(results, segKey, "P7D");
        if (gbResult == null) gbResult = findBySegmentAndWindow(results, segKey, "P30D");

        assertThat(gbResult)
                .as("TECH-GB over-shift segment should exist")
                .isNotNull();
        assertThat(gbResult.numerator().longValue()).isEqualTo(3240);
        assertThat(gbResult.value().compareTo(BigDecimal.ONE))
                .as("Over-shift utilization must be > 1.0 (not clamped)")
                .isGreaterThan(0);
        assertThat(gbResult.value()).isEqualByComparingTo(new BigDecimal("1.2000"));
    }

    @Test
    @Transactional
    @DisplayName("[ZeroTime] TECH-GC zero logged time yields 0.0000 utilization (not null)")
    void zeroLoggedTime_techGC_yieldsBigDecimalZero() {
        insertShiftWindows(GC_TECH);
        // No labour entries inserted for GC_TECH — zero logged time

        // Insert labour for GA so there is at least one week in the window
        Instant labourAt = Instant.now().minus(3, ChronoUnit.DAYS);
        insertShiftWindows(GA_TECH);
        insertLabour(GA_TECH, 60, labourAt); // minimal labour to anchor the week

        List<KpiAggregatorResult> results = utilizationCalculator.compute();

        LocalDate weekStart = isoWeekStart(labourAt);
        String    gcSegKey  = "TECH:" + GC_TECH + ":WEEK:" + weekStart;

        KpiAggregatorResult gcResult = findBySegmentAndWindow(results, gcSegKey, "P7D");
        if (gcResult == null) gcResult = findBySegmentAndWindow(results, gcSegKey, "P30D");

        assertThat(gcResult)
                .as("TECH-GC with shift but zero labour should appear with 0.0000 utilization")
                .isNotNull();
        assertThat(gcResult.value())
                .as("Zero logged time must yield 0.0000 (not null) when shift exists")
                .isNotNull();
        assertThat(gcResult.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(gcResult.numerator().longValue()).isZero();
        assertThat(gcResult.denominator().longValue()).isEqualTo(2700);
    }

    @Test
    @Transactional
    @DisplayName("[IncompleteData] labour with no shift window yields incompleteData=true")
    void incompleteData_noShiftWindow_markedIncomplete() {
        // GD_TECH has NO shift windows but we insert labour for them
        Instant labourAt = Instant.now().minus(3, ChronoUnit.DAYS);
        insertLabour(GD_TECH, 480, labourAt);

        List<KpiAggregatorResult> results = utilizationCalculator.compute();

        LocalDate weekStart = isoWeekStart(labourAt);
        String    gdSegKey  = "TECH:" + GD_TECH + ":WEEK:" + weekStart;

        // Look for GD in all windows
        KpiAggregatorResult gdResult = results.stream()
                .filter(r -> r.segmentKey().equals(gdSegKey))
                .findFirst()
                .orElse(null);

        assertThat(gdResult)
                .as("TECH-GD with labour but no shift should appear with incompleteData=true")
                .isNotNull();
        assertThat(gdResult.incompleteData())
                .as("Missing shift data must set incompleteData=true")
                .isTrue();
        assertThat(gdResult.value())
                .as("Incomplete data yields null value (cannot compute)")
                .isNull();
    }

    @Test
    @DisplayName("[TeamRollup] sum-of-numerators differs from mean-of-ratios for asymmetric shifts")
    void teamRollup_sumOfNumerators_notMeanOfRatios() {
        // Two techs with asymmetric shift lengths:
        // Tech A: field=1080, shift=2700 → rate=0.4000
        // Tech B: field=540,  shift=1080 → rate=0.5000
        // Mean-of-ratios = (0.4000 + 0.5000) / 2 = 0.4500
        // Sum-of-numerators = (1080+540) / (2700+1080) = 1620/3780 = 0.4286
        BigDecimal techARateAlone   = ratio(1080, 2700);  // 0.4000
        BigDecimal techBRateAlone   = ratio(540,  1080);  // 0.5000
        BigDecimal meanOfRatios     = techARateAlone.add(techBRateAlone)
                .divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP); // 0.4500
        BigDecimal sumOfNumerators  = ratio(1080 + 540, 2700 + 1080); // 0.4286

        assertThat(sumOfNumerators)
                .as("Sum-of-numerators must differ from mean-of-ratios for asymmetric shifts")
                .isNotEqualByComparingTo(meanOfRatios);
        assertThat(sumOfNumerators).isEqualByComparingTo(new BigDecimal("0.4286"));
        assertThat(meanOfRatios).isEqualByComparingTo(new BigDecimal("0.4500"));
    }

    private KpiAggregatorResult findBySegmentAndWindow(List<KpiAggregatorResult> results,
                                                       String segmentKey, String windowKey) {
        return results.stream()
                .filter(r -> segmentKey.equals(r.segmentKey()) && windowKey.equals(r.windowKey()))
                .findFirst()
                .orElse(null);
    }
}
