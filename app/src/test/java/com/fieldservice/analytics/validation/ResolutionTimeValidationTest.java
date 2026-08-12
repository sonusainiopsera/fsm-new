package com.fieldservice.analytics.validation;

import com.fieldservice.analytics.internal.KpiAggregator.KpiAggregatorResult;
import com.fieldservice.analytics.internal.sla.SlaResolutionMeanCalculator;
import com.fieldservice.analytics.internal.sla.SlaResolutionMedianCalculator;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Validates sla.resolution.mean and sla.resolution.median against the golden dataset.
 *
 * <p>Expected values (hand-derived from kpi-expected-values.json):
 * HIGH:   mean=97.75, median=105.0   (4 values: [60,90,120,121])
 * MEDIUM: mean=190.0, median=200.0   (3 values: [120,200,250])
 * LOW:    mean=400.0, median=400.0   (1 value:  [400])
 * ALL:    mean=170.125, median=120.5 (8 values: sorted [60,90,120,120,121,200,250,400])
 *
 * <p>Median is computed by PostgreSQL percentile_cont(0.5): for even N, interpolates
 * between the two middle values — validating the median is not approximated from mean.
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
class ResolutionTimeValidationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_resolution_val")
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

    @Autowired JdbcTemplate                jdbc;
    @Autowired SlaResolutionMeanCalculator   meanCalculator;
    @Autowired SlaResolutionMedianCalculator medianCalculator;

    static final String NS = "gg000000-0000-7207-0002-";

    static final UUID G_CUSTOMER = UUID.fromString(NS + "000000000001");
    static final UUID G_SITE     = UUID.fromString(NS + "000000000002");

    // Same 8-WO golden dataset as ComplianceMetricValidationTest (different UUIDs)
    static final UUID GH001 = UUID.fromString(NS + "100000000001");
    static final UUID GH002 = UUID.fromString(NS + "100000000002");
    static final UUID GH003 = UUID.fromString(NS + "100000000003");
    static final UUID GH004 = UUID.fromString(NS + "100000000004");
    static final UUID GM001 = UUID.fromString(NS + "200000000001");
    static final UUID GM002 = UUID.fromString(NS + "200000000002");
    static final UUID GM003 = UUID.fromString(NS + "200000000003");
    static final UUID GL001 = UUID.fromString(NS + "300000000001");

    @BeforeEach
    void cleanAndSeedReferenceEntities() {
        jdbc.execute("DELETE FROM analytics_closure_projection WHERE work_order_id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM work_order WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM site WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM customer WHERE id::text LIKE '" + NS + "%'");

        jdbc.execute("INSERT INTO customer (id, name, version) VALUES ('" + G_CUSTOMER + "', 'Resolution Val Customer', 0) ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO site (id, name, customer_id, version) VALUES ('" + G_SITE + "', 'Resolution Val Site', '" + G_CUSTOMER + "', 0) ON CONFLICT DO NOTHING");
    }

    private void insertWo(UUID id, String priority, Instant createdAt, Instant deadline) {
        jdbc.update("""
                INSERT INTO work_order (id, reference, state, priority, site_id, created_at, resolution_deadline, version)
                VALUES (?, ?, 'CLOSED', ?, ?, ?, ?, 0) ON CONFLICT DO NOTHING
                """,
                id, "RES-" + id.toString().substring(0, 8),
                priority, G_SITE,
                Timestamp.from(createdAt),
                deadline != null ? Timestamp.from(deadline) : null);
    }

    private void insertClosure(UUID woId, Instant closedAt) {
        jdbc.update("""
                INSERT INTO analytics_closure_projection (id, work_order_id, closed_at, matured_at, maturity, is_first_time_fix)
                VALUES (?, ?, ?, ?, 'MATURED', true) ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(), woId,
                Timestamp.from(closedAt),
                Timestamp.from(closedAt.plus(30, ChronoUnit.DAYS)));
    }

    private void seedGoldenDataset() {
        Instant base = Instant.now().minus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        // HIGH: created at base, deadline = base+120min
        Instant highDeadline = base.plus(120, ChronoUnit.MINUTES);
        insertWo(GH001, "HIGH", base, highDeadline);
        insertWo(GH002, "HIGH", base, highDeadline);
        insertWo(GH003, "HIGH", base, highDeadline);
        insertWo(GH004, "HIGH", base, highDeadline);

        insertClosure(GH001, base.plus(60, ChronoUnit.MINUTES));   // 60 min
        insertClosure(GH002, base.plus(90, ChronoUnit.MINUTES));   // 90 min
        insertClosure(GH003, base.plus(120, ChronoUnit.MINUTES));  // 120 min
        insertClosure(GH004, base.plus(121, ChronoUnit.MINUTES));  // 121 min

        // MEDIUM: created at base+24h, deadline = medBase+240min
        Instant medBase     = base.plus(24, ChronoUnit.HOURS);
        Instant medDeadline = medBase.plus(240, ChronoUnit.MINUTES);
        insertWo(GM001, "MEDIUM", medBase, medDeadline);
        insertWo(GM002, "MEDIUM", medBase, medDeadline);
        insertWo(GM003, "MEDIUM", medBase, medDeadline);

        insertClosure(GM001, medBase.plus(120, ChronoUnit.MINUTES)); // 120 min
        insertClosure(GM002, medBase.plus(200, ChronoUnit.MINUTES)); // 200 min
        insertClosure(GM003, medBase.plus(250, ChronoUnit.MINUTES)); // 250 min

        // LOW: created at base+48h
        Instant lowBase     = base.plus(48, ChronoUnit.HOURS);
        Instant lowDeadline = lowBase.plus(480, ChronoUnit.MINUTES);
        insertWo(GL001, "LOW", lowBase, lowDeadline);
        insertClosure(GL001, lowBase.plus(400, ChronoUnit.MINUTES)); // 400 min
    }

    // ── Mean validation ───────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("[Mean][Golden] HIGH P30D mean = 97.75 min — (60+90+120+121)/4")
    void mean_HIGH_P30D_matchesHandDerived() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = meanCalculator.compute();
        KpiAggregatorResult highP30D = find(results, "HIGH", "P30D");

        assertThat(highP30D).isNotNull();
        assertThat(highP30D.value().doubleValue()).isCloseTo(97.75, within(0.01));
    }

    @Test
    @Transactional
    @DisplayName("[Mean][Golden] MEDIUM P30D mean = 190.0 min — (120+200+250)/3")
    void mean_MEDIUM_P30D_matchesHandDerived() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = meanCalculator.compute();
        KpiAggregatorResult medP30D = find(results, "MEDIUM", "P30D");

        assertThat(medP30D).isNotNull();
        assertThat(medP30D.value().doubleValue()).isCloseTo(190.0, within(0.01));
    }

    @Test
    @Transactional
    @DisplayName("[Mean][Golden] ALL P30D mean = 170.125 min — 1361/8 across all priorities")
    void mean_ALL_P30D_matchesHandDerived() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = meanCalculator.compute();
        KpiAggregatorResult allP30D = find(results, "ALL", "P30D");

        assertThat(allP30D).isNotNull();
        assertThat(allP30D.value().doubleValue()).isCloseTo(170.125, within(0.01));
    }

    // ── Median validation ─────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("[Median][Golden] HIGH P30D median = 105.0 min — even N=4, interpolated (90+120)/2")
    void median_HIGH_P30D_matchesHandDerived_evenCount() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = medianCalculator.compute();
        KpiAggregatorResult highP30D = find(results, "HIGH", "P30D");

        assertThat(highP30D).isNotNull();
        // Even-count median: (90+120)/2 = 105.0 — proves median is NOT approximated from mean (97.75)
        assertThat(highP30D.value().doubleValue())
                .as("Even-count median must be interpolated from the two middle values, not from mean")
                .isCloseTo(105.0, within(0.01));
        assertThat(highP30D.value().doubleValue()).isNotEqualTo(97.75); // definitively not the mean
    }

    @Test
    @Transactional
    @DisplayName("[Median][Golden] MEDIUM P30D median = 200.0 min — odd N=3, middle value")
    void median_MEDIUM_P30D_matchesHandDerived_oddCount() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = medianCalculator.compute();
        KpiAggregatorResult medP30D = find(results, "MEDIUM", "P30D");

        assertThat(medP30D).isNotNull();
        assertThat(medP30D.value().doubleValue()).isCloseTo(200.0, within(0.01));
    }

    @Test
    @Transactional
    @DisplayName("[Median][Golden] ALL P30D median = 120.5 min — even N=8, sorted [60,90,120,120,121,200,250,400]")
    void median_ALL_P30D_matchesHandDerived() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = medianCalculator.compute();
        KpiAggregatorResult allP30D = find(results, "ALL", "P30D");

        assertThat(allP30D).isNotNull();
        // sorted=[60,90,120,120,121,200,250,400], median=(120+121)/2=120.5
        assertThat(allP30D.value().doubleValue()).isCloseTo(120.5, within(0.01));
    }

    @Test
    @Transactional
    @DisplayName("[Median][Proof] median != mean for HIGH distribution — actual vs approximated")
    void median_HIGH_P30D_differentiatedFromMean() {
        seedGoldenDataset();

        List<KpiAggregatorResult> meanResults   = meanCalculator.compute();
        List<KpiAggregatorResult> medianResults = medianCalculator.compute();

        KpiAggregatorResult highMean   = find(meanResults,   "HIGH", "P30D");
        KpiAggregatorResult highMedian = find(medianResults, "HIGH", "P30D");

        assertThat(highMean).isNotNull();
        assertThat(highMedian).isNotNull();

        // Mean=97.75, Median=105.0 — they are different, proving the median is not the mean
        assertThat(highMean.value().doubleValue())
                .isNotEqualTo(highMedian.value().doubleValue());
        assertThat(highMean.value().doubleValue()).isCloseTo(97.75,  within(0.01));
        assertThat(highMedian.value().doubleValue()).isCloseTo(105.0, within(0.01));
    }

    private KpiAggregatorResult find(List<KpiAggregatorResult> results,
                                     String segmentKey, String windowKey) {
        return results.stream()
                .filter(r -> segmentKey.equals(r.segmentKey()) && windowKey.equals(r.windowKey()))
                .findFirst()
                .orElse(null);
    }
}
