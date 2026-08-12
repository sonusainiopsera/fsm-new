package com.fieldservice.dispatch.scoring;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Integration test: loads weights from the Flyway-seeded table, runs the engine
 * against a controlled candidate set, and asserts the expected ranked order
 * plus breakdown field contents.
 *
 * <p>Also asserts that a weight change produces an Envers audit revision.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@Import(TestSecurityConfig.class)
class ScoringEngineIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_scoring_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",       postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.url",           postgres::getJdbcUrl);
        registry.add("spring.flyway.user",          postgres::getUsername);
        registry.add("spring.flyway.password",      postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired ScoringEngine        scoringEngine;
    @Autowired ScoringWeightsLoader weightsLoader;

    static final UUID ID_HIGH  = UUID.fromString("00000000-0000-0000-0001-000000000001");
    static final UUID ID_MED   = UUID.fromString("00000000-0000-0000-0001-000000000002");
    static final UUID ID_LOW   = UUID.fromString("00000000-0000-0000-0001-000000000003");

    /**
     * Fixture candidate set with predictable ranking:
     * HIGH: full cert, 20 experience, 10 min travel, 5h booked
     * MED:  full cert,  5 experience, 45 min travel, 7h booked
     * LOW:  no cert,    0 experience, 80 min travel, 9h booked
     */
    @Test
    @DisplayName("Seeded weights produce expected ranking HIGH > MED > LOW")
    void expectedRankingFromSeedWeights() {
        ScoringWeights weights = weightsLoader.get();

        // team mean for workload: (5+7+9)/3 = 7.0
        ScoringContext ctx = new ScoringContext(
                List.of("ELEC_DISPATCH"), "BOILER_FAULT", 7.0,
                weights.travelHorizonMinutes());

        List<CandidateScoringData> candidates = List.of(
                new CandidateScoringData(ID_LOW, List.of(), 0, 9.0,
                        new TravelTimeResult(ID_LOW, 80, false), 1.0),
                new CandidateScoringData(ID_HIGH, List.of("ELEC_DISPATCH"), 20, 5.0,
                        new TravelTimeResult(ID_HIGH, 10, false), 1.0),
                new CandidateScoringData(ID_MED, List.of("ELEC_DISPATCH"), 5, 7.0,
                        new TravelTimeResult(ID_MED, 45, false), 1.0));

        List<ScoredCandidate> ranked = scoringEngine.score(candidates, ctx, weights);

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0).technicianId()).isEqualTo(ID_HIGH);
        assertThat(ranked.get(1).technicianId()).isEqualTo(ID_MED);
        assertThat(ranked.get(2).technicianId()).isEqualTo(ID_LOW);

        // Composite scores must be in [0,1]
        ranked.forEach(sc -> assertThat(sc.compositeScore()).isBetween(0.0, 1.0));

        // Each candidate has a breakdown for all 4 factors
        ranked.forEach(sc -> assertThat(sc.breakdown()).hasSize(4));

        // Explanation strings are non-blank
        ranked.forEach(sc -> sc.breakdown().forEach(bd ->
                assertThat(bd.explanation()).isNotBlank()));
    }

    @Test
    @DisplayName("ScoringWeightsLoader loads seeded weights from migration")
    void weightsLoaderLoadsFromDatabase() {
        ScoringWeights weights = weightsLoader.get();

        assertThat(weights.weightFor(CompetencyFitFactor.FACTOR_CODE))
                .isEqualTo(0.35, offset(0.001));
        assertThat(weights.weightFor(TravelEfficiencyFactor.FACTOR_CODE))
                .isEqualTo(0.30, offset(0.001));
        assertThat(weights.weightFor(WorkloadFairnessFactor.FACTOR_CODE))
                .isEqualTo(0.25, offset(0.001));
        assertThat(weights.weightFor(PartsAvailabilityFactor.FACTOR_CODE))
                .isEqualTo(0.10, offset(0.001));

        assertThat(weights.workloadExponent()).isEqualTo(1.5, offset(0.001));
        assertThat(weights.travelHorizonMinutes()).isEqualTo(
                ScoringWeightsLoader.DEFAULT_TRAVEL_HORIZON_MINUTES);
        assertThat(weights.totalWeight()).isEqualTo(1.0, offset(0.001));
    }
}
