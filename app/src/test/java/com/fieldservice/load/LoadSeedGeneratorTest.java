package com.fieldservice.load;

import com.fieldservice.fixtures.DeterministicIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoadSeedGenerator} — verifies determinism and structural invariants.
 */
class LoadSeedGeneratorTest {

    @BeforeEach
    void resetSequence() {
        DeterministicIds.resetSequence();
    }

    // ─── Determinism ────────────────────────────────────────────────────────

    @Test
    void generateIsDeterministicAcrossTwoInvocations() {
        DeterministicIds.resetSequence();
        List<LoadSeedGenerator.SeedTechnician> first = LoadSeedGenerator.generate();

        DeterministicIds.resetSequence();
        List<LoadSeedGenerator.SeedTechnician> second = LoadSeedGenerator.generate();

        assertThat(first).hasSize(second.size());
        for (int i = 0; i < first.size(); i++) {
            UUID firstId  = first.get(i).technicianWithCerts().technician().getId();
            UUID secondId = second.get(i).technicianWithCerts().technician().getId();
            assertThat(firstId)
                    .as("technician ID at index %d must be deterministic", i)
                    .isEqualTo(secondId);
        }
    }

    @Test
    void employeeNumbersAreIdenticalAcrossInvocations() {
        DeterministicIds.resetSequence();
        List<LoadSeedGenerator.SeedTechnician> first = LoadSeedGenerator.generate();

        DeterministicIds.resetSequence();
        List<LoadSeedGenerator.SeedTechnician> second = LoadSeedGenerator.generate();

        for (int i = 0; i < first.size(); i++) {
            String firstEmp  = first.get(i).technicianWithCerts().technician().getEmployeeNo();
            String secondEmp = second.get(i).technicianWithCerts().technician().getEmployeeNo();
            assertThat(firstEmp).isEqualTo(secondEmp);
        }
    }

    // ─── Pool size ──────────────────────────────────────────────────────────

    @Test
    void generates200Technicians() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        assertThat(pool).hasSize(LoadSeedGenerator.POOL_SIZE);
    }

    // ─── Unique IDs and employee numbers ──────────────────────────────────────

    @Test
    void allTechnicianIdsAreDistinct() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        Set<UUID> ids = new HashSet<>();
        for (LoadSeedGenerator.SeedTechnician s : pool) {
            ids.add(s.technicianWithCerts().technician().getId());
        }
        assertThat(ids).hasSize(LoadSeedGenerator.POOL_SIZE);
    }

    @Test
    void allEmployeeNumbersAreDistinct() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        Set<String> empNos = new HashSet<>();
        for (LoadSeedGenerator.SeedTechnician s : pool) {
            empNos.add(s.technicianWithCerts().technician().getEmployeeNo());
        }
        assertThat(empNos).hasSize(LoadSeedGenerator.POOL_SIZE);
    }

    @Test
    void allEmailsAreDistinct() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        Set<String> emails = new HashSet<>();
        for (LoadSeedGenerator.SeedTechnician s : pool) {
            emails.add(s.technicianWithCerts().technician().getUser().getEmail());
        }
        assertThat(emails).hasSize(LoadSeedGenerator.POOL_SIZE);
    }

    // ─── Positions within bounding box ────────────────────────────────────────

    @Test
    void allPositionsAreWithinSyntheticBoundingBox() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        BigDecimal latMin = BigDecimal.valueOf(37.7749 - 0.25);
        BigDecimal latMax = BigDecimal.valueOf(37.7749 + 0.25);
        BigDecimal lonMin = BigDecimal.valueOf(-122.4194 - 0.25);
        BigDecimal lonMax = BigDecimal.valueOf(-122.4194 + 0.25);

        for (LoadSeedGenerator.SeedTechnician s : pool) {
            assertThat(s.latitude())
                    .isBetween(latMin, latMax);
            assertThat(s.longitude())
                    .isBetween(lonMin, lonMax);
        }
    }

    @Test
    void allPositionsAreDistinct() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        Set<String> positions = new HashSet<>();
        for (LoadSeedGenerator.SeedTechnician s : pool) {
            positions.add(s.latitude() + "," + s.longitude());
        }
        // At least 95% distinct (grid may have a few overlaps at boundaries)
        assertThat(positions.size()).isGreaterThanOrEqualTo((int) (LoadSeedGenerator.POOL_SIZE * 0.95));
    }

    // ─── Certification mix ───────────────────────────────────────────────────

    @Test
    void poolContainsTechniciansWithMixedCertificationStatus() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        long withCerts = pool.stream()
                .filter(s -> !s.technicianWithCerts().certifications().isEmpty())
                .count();
        long withExpiredCerts = pool.stream()
                .filter(s -> s.technicianWithCerts().certifications().stream()
                        .anyMatch(c -> c.getCertReference().contains("EXP")))
                .count();

        assertThat(withCerts).isEqualTo(LoadSeedGenerator.POOL_SIZE);
        assertThat(withExpiredCerts).isGreaterThan(0).as("expired cert bucket should be populated");
    }

    // ─── Anonymization ────────────────────────────────────────────────────────

    @Test
    void noRealDataPatternInEmails() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        for (LoadSeedGenerator.SeedTechnician s : pool) {
            String email = s.technicianWithCerts().technician().getUser().getEmail();
            assertThat(email)
                    .as("seed emails must use .invalid TLD per RFC 2606")
                    .endsWith(".invalid");
        }
    }

    @Test
    void noRealDataPatternInPhones() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        for (LoadSeedGenerator.SeedTechnician s : pool) {
            String phone = s.technicianWithCerts().technician().getPhone();
            assertThat(phone)
                    .as("seed phones must use NANP load-test range +1555")
                    .startsWith("+1555");
        }
    }

    // ─── SQL output ──────────────────────────────────────────────────────────

    @Test
    void toSqlProducesNonEmptyScript() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        String sql = LoadSeedGenerator.toSql(pool);

        assertThat(sql).isNotBlank();
        assertThat(sql).contains("INSERT INTO app_user");
        assertThat(sql).contains("INSERT INTO technician");
        assertThat(sql).contains("INSERT INTO technician_position");
        assertThat(sql).contains("ON CONFLICT");
    }

    @Test
    void toSqlContainsAllTechnicians() {
        List<LoadSeedGenerator.SeedTechnician> pool = LoadSeedGenerator.generate();
        String sql = LoadSeedGenerator.toSql(pool);

        for (LoadSeedGenerator.SeedTechnician s : pool) {
            String empNo = s.technicianWithCerts().technician().getEmployeeNo();
            assertThat(sql).contains(empNo);
        }
    }
}
