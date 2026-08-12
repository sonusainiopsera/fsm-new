package com.fieldservice.dispatch.scoring;

import com.fieldservice.dispatch.scoring.factors.CompetencyFitFactor;
import com.fieldservice.dispatch.scoring.factors.PartsAvailabilityFactor;
import com.fieldservice.dispatch.scoring.factors.TravelEfficiencyFactor;
import com.fieldservice.dispatch.scoring.factors.WorkloadFairnessFactor;
import com.fieldservice.dispatch.scoring.persistence.DispatchScoringConfigRepository;
import com.fieldservice.dispatch.scoring.persistence.DispatchScoringWeight;
import com.fieldservice.dispatch.scoring.persistence.DispatchScoringWeightRepository;
import com.fieldservice.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration test for the scoring subsystem against a live Testcontainers database.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Weights loaded from the Flyway-seeded dispatch_scoring_weight table.</li>
 *   <li>ScoringEngine produces the expected ranking from the fixture candidates.</li>
 *   <li>Per-factor breakdown fields are populated.</li>
 *   <li>An Envers audit revision is created after a weight update.</li>
 * </ul>
 */
@Transactional
class ScoringWeightsIT extends AbstractIntegrationTest {

    // Fixture technician IDs from V134__dispatch_scoring_fixtures.sql
    private static final UUID TECH_A = UUID.fromString("e0000000-0001-7000-8000-000000000001");
    private static final UUID TECH_B = UUID.fromString("e0000000-0002-7000-8000-000000000002");
    private static final UUID TECH_C = UUID.fromString("e0000000-0003-7000-8000-000000000003");

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private ScoringEngine scoringEngine;

    @Autowired
    private ScoringWeightsLoader weightsLoader;

    @Autowired
    private DispatchScoringWeightRepository weightRepo;

    @Autowired
    private DispatchScoringConfigRepository configRepo;

    @Test
    void weightsLoader_loadsFromDb_returnsFourActiveEntries() {
        weightsLoader.invalidate();
        ScoringWeights weights = weightsLoader.load();

        assertThat(weights.entries()).hasSize(4);
        assertThat(weights.entries().stream().filter(ScoringWeights.WeightEntry::active).count())
                .isEqualTo(4);
        assertThat(weights.workloadPenaltyExponent()).isGreaterThan(1.0);
    }

    @Test
    void scoringEngine_fixtureRanking_expectedOrderABC() {
        // Team mean = (6 + 7 + 9) / 3 = 7.33
        double teamMean = (6.0 + 7.0 + 9.0) / 3.0;

        weightsLoader.invalidate();
        ScoringWeights weights = weightsLoader.load();

        List<ScoringContext> candidates = List.of(
                // TECH_A: cert 100%, 5 exp, 30 min, 6h booked (below mean), parts ok
                new ScoringContext(TECH_A, List.of("ELEC_LV"), Set.of("ELEC_LV"),
                        5, new TravelTimeEstimate(30, false), 6.0, teamMean, true),
                // TECH_B: cert 100%, 2 exp, 60 min, 7h booked (at mean), parts ok
                new ScoringContext(TECH_B, List.of("ELEC_LV"), Set.of("ELEC_LV"),
                        2, new TravelTimeEstimate(60, false), 7.0, teamMean, true),
                // TECH_C: cert 100%, 8 exp, 90 min, 9h booked (above mean), no parts
                new ScoringContext(TECH_C, List.of("ELEC_LV"), Set.of("ELEC_LV"),
                        8, new TravelTimeEstimate(90, false), 9.0, teamMean, false)
        );

        List<ScoredCandidate> ranked = scoringEngine.rank(candidates, weights);

        assertThat(ranked).hasSize(3);
        // Expected ranking: A (low travel + below mean) > B (mid) > C (high travel + overloaded + no parts)
        assertThat(ranked.get(0).technicianId()).isEqualTo(TECH_A);
        assertThat(ranked.get(1).technicianId()).isEqualTo(TECH_B);
        assertThat(ranked.get(2).technicianId()).isEqualTo(TECH_C);
    }

    @Test
    void scoringEngine_breakdownContainsAllActiveFactors() {
        weightsLoader.invalidate();
        ScoringWeights weights = weightsLoader.load();

        ScoringContext ctx = new ScoringContext(TECH_A, List.of("ELEC_LV"), Set.of("ELEC_LV"),
                5, new TravelTimeEstimate(30, false), 6.0, 7.0, true);
        List<ScoredCandidate> ranked = scoringEngine.rank(List.of(ctx), weights);

        assertThat(ranked.get(0).breakdown())
                .extracting(FactorBreakdown::factorCode)
                .containsExactlyInAnyOrder(
                        CompetencyFitFactor.CODE,
                        TravelEfficiencyFactor.CODE,
                        WorkloadFairnessFactor.CODE,
                        PartsAvailabilityFactor.CODE);

        for (FactorBreakdown bd : ranked.get(0).breakdown()) {
            assertThat(bd.normalisedValue()).isBetween(0.0, 1.0);
            assertThat(bd.explanation()).isNotBlank();
            assertThat(bd.weight()).isGreaterThan(0.0);
        }
    }

    @Test
    void weightUpdate_createsEnversAuditRevision() {
        DispatchScoringWeight row = weightRepo
                .findAll().stream()
                .filter(w -> CompetencyFitFactor.CODE.equals(w.getFactorCode()))
                .findFirst()
                .orElseThrow();
        UUID id = row.getId();
        BigDecimal original = row.getWeight();

        row.setWeight(original.add(BigDecimal.valueOf(0.1)));
        weightRepo.saveAndFlush(row);
        em.flush();

        // Query the Envers AUD table directly — must have at least one revision for this row
        @SuppressWarnings("unchecked")
        List<Object[]> audRows = em.createNativeQuery(
                "SELECT id, rev, revtype, weight FROM dispatch_scoring_weight_aud WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        assertThat(audRows).isNotEmpty();
        // revtype 1 = MOD (Hibernate Envers RevisionType ordinal)
        boolean hasModRevision = audRows.stream()
                .anyMatch(r -> ((Number) r[2]).intValue() == 1);
        assertThat(hasModRevision).isTrue();
    }

    @Test
    void scoringWeightsLoader_invalidate_reloadsFromDb() {
        weightsLoader.invalidate();
        ScoringWeights first = weightsLoader.load();
        weightsLoader.invalidate();
        ScoringWeights second = weightsLoader.load();

        assertThat(first.workloadPenaltyExponent())
                .isCloseTo(second.workloadPenaltyExponent(), within(0.0001));
    }
}
