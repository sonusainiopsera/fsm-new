package com.fieldservice.load;

import com.fieldservice.load.LoadSeedGenerator.CertStatus;
import com.fieldservice.load.LoadSeedGenerator.TechnicianSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LoadSeedGenerator}.
 *
 * <p>No Spring context, no I/O. Verifies:
 * <ul>
 *   <li>Determinism: two independently constructed instances produce identical output.</li>
 *   <li>Completeness: 200 specs, correct cert distribution, all active flags set correctly.</li>
 *   <li>Position uniqueness: no two technicians share the same coordinates.</li>
 *   <li>SQL correctness markers: generated SQL contains mandatory structural tokens.</li>
 *   <li>No PII: no real names, real emails, or real addresses in generated output.</li>
 * </ul>
 */
class LoadSeedGeneratorTest {

    LoadSeedGenerator generator1;
    LoadSeedGenerator generator2;

    @BeforeEach
    void setUp() {
        generator1 = new LoadSeedGenerator();
        generator2 = new LoadSeedGenerator();
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("generate() is deterministic across two independent instances")
    void generate_deterministic() {
        String sql1 = generator1.generate();
        String sql2 = generator2.generate();

        assertThat(sql1).isEqualTo(sql2);
    }

    @Test
    @DisplayName("buildSpecs() is deterministic across two independent instances")
    void buildSpecs_deterministic() {
        List<TechnicianSpec> specs1 = generator1.buildSpecs();
        List<TechnicianSpec> specs2 = generator2.buildSpecs();

        assertThat(specs1).isEqualTo(specs2);
    }

    // ── Completeness ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("exactly 200 technician specs are generated")
    void buildSpecs_200Total() {
        List<TechnicianSpec> specs = generator1.buildSpecs();
        assertThat(specs).hasSize(200);
    }

    @Test
    @DisplayName("spec indices are 1-based sequential from 1 to 200")
    void buildSpecs_indicesSequential() {
        List<TechnicianSpec> specs = generator1.buildSpecs();
        assertThat(specs.stream().map(TechnicianSpec::index).min(Integer::compare)).contains(1);
        assertThat(specs.stream().map(TechnicianSpec::index).max(Integer::compare)).contains(200);
    }

    @Test
    @DisplayName("140 technicians have VALID cert status")
    void buildSpecs_140_validCerts() {
        long count = generator1.buildSpecs().stream()
                .filter(s -> s.certStatus() == CertStatus.VALID)
                .count();
        assertThat(count).isEqualTo(140);
    }

    @Test
    @DisplayName("25 technicians have EXPIRED cert status")
    void buildSpecs_25_expiredCerts() {
        long count = generator1.buildSpecs().stream()
                .filter(s -> s.certStatus() == CertStatus.EXPIRED)
                .count();
        assertThat(count).isEqualTo(25);
    }

    @Test
    @DisplayName("35 technicians have MISSING cert status (missing + inactive)")
    void buildSpecs_35_missingOrInactive() {
        long count = generator1.buildSpecs().stream()
                .filter(s -> s.certStatus() == CertStatus.MISSING)
                .count();
        assertThat(count).isEqualTo(35);
    }

    @Test
    @DisplayName("technicians 1-185 are active; 186-200 are inactive")
    void buildSpecs_activeFlags() {
        List<TechnicianSpec> specs = generator1.buildSpecs();

        long activeCount = specs.stream().filter(TechnicianSpec::active).count();
        long inactiveCount = specs.stream().filter(s -> !s.active()).count();

        assertThat(activeCount).isEqualTo(185);
        assertThat(inactiveCount).isEqualTo(15);
    }

    // ── Position uniqueness ───────────────────────────────────────────────────

    @Test
    @DisplayName("all 200 positions are distinct (no duplicate lat/lon pairs)")
    void buildSpecs_positionsDistinct() {
        List<TechnicianSpec> specs = generator1.buildSpecs();
        Set<String> positions = specs.stream()
                .map(s -> s.latitude() + "," + s.longitude())
                .collect(Collectors.toSet());

        assertThat(positions).hasSize(200);
    }

    @Test
    @DisplayName("all positions are within the synthetic bounding box")
    void buildSpecs_positionsInBoundingBox() {
        List<TechnicianSpec> specs = generator1.buildSpecs();
        assertThat(specs).allSatisfy(s -> {
            assertThat(s.latitude()).isBetween(LoadSeedGenerator.LAT_MIN, LoadSeedGenerator.LAT_MAX);
            assertThat(s.longitude()).isBetween(LoadSeedGenerator.LON_MIN, LoadSeedGenerator.LON_MAX);
        });
    }

    // ── SQL correctness markers ───────────────────────────────────────────────

    @Test
    @DisplayName("generated SQL contains idempotent DELETE clauses")
    void generate_containsDeleteClauses() {
        String sql = generator1.generate();
        assertThat(sql).contains("DELETE FROM technician_position");
        assertThat(sql).contains("DELETE FROM technician_certification");
        assertThat(sql).contains("DELETE FROM technician");
        assertThat(sql).contains("DELETE FROM app_user");
    }

    @Test
    @DisplayName("generated SQL contains ON CONFLICT DO NOTHING clauses for idempotency")
    void generate_containsOnConflictDoNothing() {
        String sql = generator1.generate();
        assertThat(sql).contains("ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("generated SQL uses the WO-206 UUID namespace (cc000000-0000-7206)")
    void generate_usesCorrectUuidNamespace() {
        String sql = generator1.generate();
        assertThat(sql).contains("cc000000-0000-7206");
    }

    // ── No PII ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no real email domains in generated SQL (only loadtest.example)")
    void generate_noRealEmailDomains() {
        String sql = generator1.generate();
        assertThat(sql).doesNotContain("@gmail.com");
        assertThat(sql).doesNotContain("@yahoo.com");
        assertThat(sql).doesNotContain("@example.com"); // different from loadtest.example
        assertThat(sql).contains("@loadtest.example");
    }

    @Test
    @DisplayName("no real UK postcodes in generated SQL")
    void generate_noRealPostcodesInTechnicianData() {
        String sql = generator1.generate();
        // The only real-ish postcode should be in the site placeholder, not the technician rows
        long loadTechOccurrences = sql.lines()
                .filter(line -> line.contains("Load Tech") || line.contains("LTECH"))
                .filter(line -> line.matches(".*[A-Z]{1,2}[0-9]{1,2}\\s[0-9][A-Z]{2}.*"))
                .count();
        assertThat(loadTechOccurrences).isZero();
    }
}
