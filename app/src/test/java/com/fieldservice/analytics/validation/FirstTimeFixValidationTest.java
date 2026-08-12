package com.fieldservice.analytics.validation;

import com.fieldservice.analytics.internal.KpiAggregator.KpiAggregatorResult;
import com.fieldservice.analytics.internal.quality.FirstTimeFixCalculator;
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

/**
 * Validates quality.first_time_fix.matured against golden datasets.
 *
 * <p>Golden dataset P90D (all MATURED):
 * GF001: MATURED, HVAC, asset=GA, fault=LEAK, is_first_time_fix=true
 * GF002: MATURED, HVAC, asset=GA, fault=LEAK, is_first_time_fix=false (repeat within 30 days)
 * GF003: MATURED, HVAC, asset=GB, fault=LEAK, is_first_time_fix=true (different asset)
 * GF004: MATURED, ELEC, asset=GC, fault=SHORT, is_first_time_fix=true
 * GF005: PROVISIONAL (recent closure, excluded from matured rate)
 * GF006: MATURED, null asset_id (unclassifiable, excluded from rate)
 *
 * Expected P90D ALL: numerator=3, denominator=4, rate=0.7500
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
class FirstTimeFixValidationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_ftf_val")
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

    @Autowired JdbcTemplate           jdbc;
    @Autowired FirstTimeFixCalculator ftfCalculator;

    static final String NS = "gg000000-0000-7207-0003-";

    static final UUID G_CUSTOMER = UUID.fromString(NS + "000000000001");
    static final UUID G_SITE     = UUID.fromString(NS + "000000000002");
    static final UUID G_ASSET_A  = UUID.fromString(NS + "000000000010"); // HVAC asset A
    static final UUID G_ASSET_B  = UUID.fromString(NS + "000000000011"); // HVAC asset B
    static final UUID G_ASSET_C  = UUID.fromString(NS + "000000000012"); // ELEC asset C

    // Closure projection IDs for the golden dataset
    static final UUID GF001 = UUID.fromString(NS + "100000000001"); // MATURED FTF
    static final UUID GF002 = UUID.fromString(NS + "100000000002"); // MATURED repeat
    static final UUID GF003 = UUID.fromString(NS + "100000000003"); // MATURED FTF
    static final UUID GF004 = UUID.fromString(NS + "100000000004"); // MATURED FTF
    static final UUID GF005 = UUID.fromString(NS + "100000000005"); // PROVISIONAL (excluded)
    static final UUID GF006 = UUID.fromString(NS + "100000000006"); // null asset (excluded)

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM analytics_closure_projection WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM work_order WHERE site_id::text = '" + G_SITE + "'");
        jdbc.execute("DELETE FROM asset WHERE site_id::text = '" + G_SITE + "'");
        jdbc.execute("DELETE FROM site WHERE id::text LIKE '" + NS + "%'");
        jdbc.execute("DELETE FROM customer WHERE id::text LIKE '" + NS + "%'");

        jdbc.execute("INSERT INTO customer (id, name, version) VALUES ('" + G_CUSTOMER + "', 'FTF Val Customer', 0) ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO site (id, name, customer_id, version) VALUES ('" + G_SITE + "', 'FTF Val Site', '" + G_CUSTOMER + "', 0) ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO asset (id, site_id, version) VALUES ('" + G_ASSET_A + "', '" + G_SITE + "', 0) ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO asset (id, site_id, version) VALUES ('" + G_ASSET_B + "', '" + G_SITE + "', 0) ON CONFLICT DO NOTHING");
        jdbc.execute("INSERT INTO asset (id, site_id, version) VALUES ('" + G_ASSET_C + "', '" + G_SITE + "', 0) ON CONFLICT DO NOTHING");
    }

    // Insert a closure projection directly (maturity and is_first_time_fix are explicit)
    private void insertClosure(UUID projId, UUID assetId, String faultKey, String assetCategory,
                               String maturity, boolean isFtf, Instant closedAt) {
        Instant maturedAt = closedAt.plus(30, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO analytics_closure_projection
                    (id, work_order_id, asset_id, fault_key, asset_category, closed_at, matured_at, maturity, is_first_time_fix)
                VALUES (?, gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                projId, assetId, faultKey, assetCategory,
                Timestamp.from(closedAt),
                Timestamp.from(maturedAt),
                maturity, isFtf);
    }

    private void seedGoldenDataset() {
        // All MATURED WOs are closed ~45 days ago (well past the 30-day maturity window)
        Instant matureBase = Instant.now().minus(45, ChronoUnit.DAYS);

        insertClosure(GF001, G_ASSET_A, "LEAK", "HVAC", "MATURED", true,  matureBase);
        insertClosure(GF002, G_ASSET_A, "LEAK", "HVAC", "MATURED", false, matureBase.plus(1, ChronoUnit.DAYS));
        insertClosure(GF003, G_ASSET_B, "LEAK", "HVAC", "MATURED", true,  matureBase.plus(2, ChronoUnit.DAYS));
        insertClosure(GF004, G_ASSET_C, "SHORT", "ELEC", "MATURED", true, matureBase.plus(3, ChronoUnit.DAYS));

        // PROVISIONAL: closed 10 days ago (matured_at = ~20 days from now, in future)
        Instant recentClosed = Instant.now().minus(10, ChronoUnit.DAYS);
        insertClosure(GF005, G_ASSET_A, "LEAK", "HVAC", "PROVISIONAL", true, recentClosed);

        // UNCLASSIFIABLE: null asset_id (excluded because asset_id IS NOT NULL check)
        insertClosure(GF006, null, "LEAK", "HVAC", "MATURED", true, matureBase.plus(4, ChronoUnit.DAYS));
    }

    // ── Golden dataset tests ──────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("[Golden] P90D ALL FTF rate = 3/4 = 0.7500 (hand-derived)")
    void goldenDataset_P90D_allFtfRate_matchesExpected() {
        seedGoldenDataset();

        List<KpiAggregatorResult> results = ftfCalculator.compute();

        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");
        assertThat(allP90D).isNotNull();
        assertThat(allP90D.numerator()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(allP90D.denominator()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(allP90D.value()).isEqualByComparingTo(new BigDecimal("0.7500"));
    }

    @Test
    @Transactional
    @DisplayName("[CohortMaturity] PROVISIONAL WO excluded from matured FTF rate")
    void cohortMaturity_provisionalExcludedFromMaturedRate() {
        // Insert ONLY the PROVISIONAL WO — denominator must be 0 (null value)
        Instant recentClosed = Instant.now().minus(10, ChronoUnit.DAYS);
        insertClosure(GF005, G_ASSET_A, "LEAK", "HVAC", "PROVISIONAL", true, recentClosed);

        List<KpiAggregatorResult> results = ftfCalculator.compute();

        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");
        // Zero MATURED classifiable WOs → value = NULL (zero denominator)
        if (allP90D != null) {
            assertThat(allP90D.value())
                    .as("PROVISIONAL WO must not appear in matured FTF denominator")
                    .isNull();
        }
    }

    @Test
    @Transactional
    @DisplayName("[CohortMaturity] MATURED WO (31 days) included; PROVISIONAL (29 days) excluded")
    void cohortMaturity_maturedIncluded_provisionalExcluded() {
        // MATURED: closed 31 days ago (matured_at = now-1 day, already matured)
        UUID maturedId     = UUID.fromString(NS + "200000000001");
        Instant maturedAt  = Instant.now().minus(31, ChronoUnit.DAYS);
        insertClosure(maturedId, G_ASSET_A, "BURST", "HVAC", "MATURED", true, maturedAt);

        // PROVISIONAL: closed 29 days ago (matured_at = now+1 day, not yet matured)
        UUID provisionalId = UUID.fromString(NS + "200000000002");
        Instant provClosed = Instant.now().minus(29, ChronoUnit.DAYS);
        insertClosure(provisionalId, G_ASSET_B, "BURST", "HVAC", "PROVISIONAL", true, provClosed);

        List<KpiAggregatorResult> results = ftfCalculator.compute();
        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");

        assertThat(allP90D).isNotNull();
        assertThat(allP90D.denominator().longValue())
                .as("Only MATURED WO must be in denominator (PROVISIONAL excluded)")
                .isEqualTo(1);
        assertThat(allP90D.value()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    @Test
    @Transactional
    @DisplayName("[Unclassifiable] WO with null asset_id is excluded from FTF rate")
    void unclassifiable_nullAsset_excludedFromFtfRate() {
        // Only a null-asset WO — denominator must be 0 → no segment with data
        Instant matureBase = Instant.now().minus(45, ChronoUnit.DAYS);
        insertClosure(GF006, null, "LEAK", "HVAC", "MATURED", true, matureBase);

        List<KpiAggregatorResult> results = ftfCalculator.compute();

        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");
        if (allP90D != null) {
            assertThat(allP90D.value())
                    .as("Null-asset WO must not count in FTF rate (unclassifiable)")
                    .isNull();
        }
    }

    @Test
    @Transactional
    @DisplayName("[Unclassifiable] WO with null fault_key is excluded from FTF rate")
    void unclassifiable_nullFaultKey_excludedFromFtfRate() {
        UUID woId          = UUID.fromString(NS + "300000000001");
        Instant matureBase = Instant.now().minus(45, ChronoUnit.DAYS);
        insertClosure(woId, G_ASSET_A, null, "HVAC", "MATURED", true, matureBase);

        List<KpiAggregatorResult> results = ftfCalculator.compute();

        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");
        if (allP90D != null) {
            assertThat(allP90D.value())
                    .as("Null-fault WO must not count in FTF rate (unclassifiable)")
                    .isNull();
        }
    }

    @Test
    @Transactional
    @DisplayName("[RepeatVisit] is_first_time_fix=false (repeat) reduces numerator but not denominator")
    void repeatVisit_reducesNumeratorOnly() {
        Instant matureBase = Instant.now().minus(45, ChronoUnit.DAYS);

        // One FTF + one repeat = denominator 2, numerator 1
        UUID ftfId    = UUID.fromString(NS + "400000000001");
        UUID repeatId = UUID.fromString(NS + "400000000002");
        insertClosure(ftfId,    G_ASSET_A, "BURST", "HVAC", "MATURED", true,  matureBase);
        insertClosure(repeatId, G_ASSET_A, "BURST", "HVAC", "MATURED", false, matureBase.plus(10, ChronoUnit.DAYS));

        List<KpiAggregatorResult> results = ftfCalculator.compute();
        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");

        assertThat(allP90D).isNotNull();
        assertThat(allP90D.numerator().longValue())
                .as("repeat visit must not count as FTF")
                .isEqualTo(1);
        assertThat(allP90D.denominator().longValue())
                .as("repeat visit IS in denominator (classifiable MATURED)")
                .isEqualTo(2);
        assertThat(allP90D.value()).isEqualByComparingTo(new BigDecimal("0.5000"));
    }

    @Test
    @Transactional
    @DisplayName("[ZeroDenominator] no MATURED classifiable WOs returns null value")
    void zeroDenominator_noMatureWOs_returnsNull() {
        // Only PROVISIONAL and null-asset — no MATURED classifiable rows
        Instant recentClosed = Instant.now().minus(10, ChronoUnit.DAYS);
        insertClosure(GF005, G_ASSET_A, "LEAK", "HVAC", "PROVISIONAL", true, recentClosed);

        List<KpiAggregatorResult> results = ftfCalculator.compute();
        KpiAggregatorResult allP90D = find(results, "ALL", "P90D");

        if (allP90D != null) {
            assertThat(allP90D.value())
                    .as("Zero MATURED denominator must yield null value, not zero")
                    .isNull();
        }
    }

    private KpiAggregatorResult find(List<KpiAggregatorResult> results,
                                     String segmentKey, String windowKey) {
        return results.stream()
                .filter(r -> segmentKey.equals(r.segmentKey()) && windowKey.equals(r.windowKey()))
                .findFirst()
                .orElse(null);
    }
}
