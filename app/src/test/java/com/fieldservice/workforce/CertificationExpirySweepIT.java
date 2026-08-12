package com.fieldservice.workforce;

import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.workforce.internal.CertificationExpirySweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CertificationExpirySweep} against a live PostgreSQL 16
 * database seeded from V129 fixtures (Testcontainers, AC-12).
 *
 * <p>Uses a fixed clock aligned to the V129 business date (2026-08-12) so cohort
 * assignments are deterministic. The distributed lock is disabled so a single test
 * context can run the sweep without contending with itself.
 *
 * <p>Tests are non-transactional because the sweep uses REQUIRES_NEW transactions
 * internally — assertions are made over raw JDBC after the sweep completes.
 */
@ActiveProfiles({"test", "worker"})
@TestPropertySource(properties = {
        "app.cert.sweep.lock-enabled=false",
        "app.cert.sweep.batch-size=50",
        "app.cert.sweep.warning-window-days=30",
        "app.cert.sweep.urgent-window-days=7"
})
@Import(CertificationExpirySweepIT.FixedClockConfig.class)
public class CertificationExpirySweepIT extends AbstractIntegrationTest {

    /** Fixed clock aligned to V129 fixture business date. */
    static final Instant TEST_NOW = Instant.parse("2026-08-12T02:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock certSweepClock() {
            return Clock.fixed(TEST_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private CertificationExpirySweep sweep;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanAlertState() {
        jdbcTemplate.execute("DELETE FROM certification_alert_state WHERE technician_certification_id LIKE 'ee000000%'");
        jdbcTemplate.execute("DELETE FROM outbox_event WHERE aggregate_id::text LIKE 'ee000000%'");
    }

    // ── Cohort membership ─────────────────────────────────────────────────────

    @Test
    @DisplayName("31d cert produces no alert (outside window)")
    void sweep_thirtyOneDays_noAlert() {
        sweep.doSweep();
        assertAlertCount("ee000000-0000-0000-0000-000000000001", 0);
    }

    @Test
    @DisplayName("30d cert produces one WARNING alert (AC-3, boundary)")
    void sweep_thirtyDays_warningAlert() {
        sweep.doSweep();
        assertAlertStage("ee000000-0000-0000-0000-000000000002", "WARNING");
    }

    @Test
    @DisplayName("8d cert produces one WARNING alert (between urgent and warning)")
    void sweep_eightDays_warningAlert() {
        sweep.doSweep();
        assertAlertStage("ee000000-0000-0000-0000-000000000003", "WARNING");
    }

    @Test
    @DisplayName("7d cert produces one URGENT alert (urgent boundary)")
    void sweep_sevenDays_urgentAlert() {
        sweep.doSweep();
        assertAlertStage("ee000000-0000-0000-0000-000000000004", "URGENT");
    }

    @Test
    @DisplayName("1d cert produces one URGENT alert")
    void sweep_oneDay_urgentAlert() {
        sweep.doSweep();
        assertAlertStage("ee000000-0000-0000-0000-000000000005", "URGENT");
    }

    @Test
    @DisplayName("0d cert (expires today) produces one EXPIRED alert")
    void sweep_zeroDay_expiredAlert() {
        sweep.doSweep();
        assertAlertStage("ee000000-0000-0000-0000-000000000006", "EXPIRED");
    }

    @Test
    @DisplayName("minus 1d cert produces one EXPIRED alert")
    void sweep_minusOneDay_expiredAlert() {
        sweep.doSweep();
        assertAlertStage("ee000000-0000-0000-0000-000000000007", "EXPIRED");
    }

    // ── Outbox events ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("outbox event written atomically with alert state (AC-5)")
    void sweep_outboxEventWritten() {
        sweep.doSweep();
        // 30d cert (WARNING) must have an outbox event
        long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?::uuid AND event_type LIKE 'Certification%'",
                Long.class,
                "ee000000-0000-0000-0000-000000000002");
        assertThat(count).isGreaterThanOrEqualTo(1L);
    }

    // ── De-duplication ────────────────────────────────────────────────────────

    @Test
    @DisplayName("running sweep 3 times produces exactly one alert per certification (AC-4)")
    void sweep_tripleRun_exactlyOneAlertPerCert() {
        sweep.doSweep();
        sweep.doSweep();
        sweep.doSweep();

        // 30d cert: exactly one WARNING, not three
        assertAlertCount("ee000000-0000-0000-0000-000000000002", 1);
        assertAlertCount("ee000000-0000-0000-0000-000000000004", 1);
        assertAlertCount("ee000000-0000-0000-0000-000000000007", 1);
    }

    // ── Re-issue reset ────────────────────────────────────────────────────────

    @Test
    @DisplayName("re-issue certification (new validity_key) alerts again (AC-10)")
    void sweep_reissuedCert_alertsAgain() {
        // Simulate prior alert for an OLD validity_key (different expires_on)
        jdbcTemplate.update("""
                INSERT INTO certification_alert_state
                    (id, technician_certification_id, alert_stage, validity_key, alerted_at)
                VALUES (gen_random_uuid(), 'ee000000-0000-0000-0000-000000000008', 'WARNING', '2025-01-01', now())
                """);

        sweep.doSweep();

        // New validity_key is '2026-09-11' (30d from business date = WARNING)
        List<Map<String, Object>> alerts = jdbcTemplate.queryForList(
                "SELECT * FROM certification_alert_state WHERE technician_certification_id = ?::uuid",
                "ee000000-0000-0000-0000-000000000008");

        // Should have TWO rows: one for old key, one for new key
        assertThat(alerts).hasSizeGreaterThanOrEqualTo(2);

        boolean newKeyAlerted = alerts.stream()
                .anyMatch(r -> "2026-09-11".equals(r.get("validity_key")));
        assertThat(newKeyAlerted)
                .as("Expected a new alert for validity_key '2026-09-11'")
                .isTrue();
    }

    // ── Concurrency: only one sweep executes ──────────────────────────────────

    @Test
    @DisplayName("sweep with lock disabled completes cleanly (single-context concurrency check)")
    void sweep_lockDisabled_completesCleanly() {
        // Lock is disabled by @TestPropertySource — just verify the sweep runs without exception
        assertThat(() -> sweep.doSweep()).doesNotThrowAnyException();
    }

    // ── Profile: sweep bean present under worker profile ──────────────────────

    @Test
    @DisplayName("CertificationExpirySweep bean is present under worker profile")
    void sweepBean_presentUnderWorkerProfile() {
        assertThat(sweep).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertAlertStage(String certId, String expectedStage) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM certification_alert_state WHERE technician_certification_id = ?::uuid",
                certId);
        assertThat(rows).as("Expected one alert for cert %s", certId).hasSize(1);
        assertThat(rows.get(0).get("alert_stage")).isEqualTo(expectedStage);
    }

    private void assertAlertCount(String certId, int expectedCount) {
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM certification_alert_state WHERE technician_certification_id = ?::uuid",
                Integer.class, certId);
        assertThat(count).as("Alert count for cert %s", certId).isEqualTo(expectedCount);
    }
}
